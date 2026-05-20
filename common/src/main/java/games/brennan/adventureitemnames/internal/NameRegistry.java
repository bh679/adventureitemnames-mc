package games.brennan.adventureitemnames.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory registry of every naming pool, chain, and selector under
 * {@code data/&lt;ns&gt;/naming/}. Three datapack-style reload listeners
 * scan all loaded namespaces (mod jars and datapacks alike), so any
 * third-party content drops into the system without code changes.
 *
 * <p>Re-populated on every {@code /reload} and on world load. The
 * composer reads only the post-load immutable maps; runtime cost during
 * gameplay is zero.</p>
 *
 * <p>This class is loader-neutral. Each loader's entrypoint calls
 * {@link #poolListener()}, {@link #chainListener()},
 * {@link #selectorListener()} to obtain listener instances and registers
 * them via the loader's native resource-manager API (NeoForge:
 * {@code AddReloadListenerEvent}; Forge: {@code AddReloadListenerEvent};
 * Fabric: {@code ResourceManagerHelper}).</p>
 *
 * <p>The chain listener uses {@code getResourceStack} so that chains
 * with {@code "replace": false} additively merge their {@code refs}
 * lists with same-id chains from lower-priority packs (mirroring vanilla
 * tag-merge convention). Pools and selectors keep the standard last-pack-wins
 * behaviour.</p>
 */
public final class NameRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final Map<ResourceLocation, NamePool> POOLS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChain> CHAINS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameSelector> SELECTORS = new LinkedHashMap<>();

    /**
     * Pack-id side tables, populated during reload from
     * {@code Resource.sourcePackId()}. Kept off the public records so the
     * {@link NamePool} / {@link NameChain} / {@link NameSelector} record
     * constructors remain a non-breaking API. The config UI groups rows
     * by these ids.
     */
    private static final Map<ResourceLocation, String> POOL_PACKS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, String> CHAIN_PACKS = new LinkedHashMap<>();
    /**
     * Per-chain ordered list of every pack id that contributed a layer
     * (low-priority first → high-priority last, matching the resource
     * stack order). Used by the UI to tag a chain with every pack that
     * shaped it, not just the winning source. Duplicate-adjacent ids
     * are coalesced.
     */
    private static final Map<ResourceLocation, List<String>> CHAIN_PACK_LAYERS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, String> SELECTOR_PACKS = new LinkedHashMap<>();

    /** Returned by the {@code packIdOf*} lookups when the resource manager couldn't pin a source. */
    public static final String UNKNOWN_PACK = "unknown";

    /** Pack id reported for user-defined custom selectors. */
    public static final String USER_PACK = "user-config";

    private static final String CHAIN_DIR = "naming/chains";
    private static final String CHAIN_EXT = ".json";

    /**
     * User-defined selectors loaded from {@code adventureitemnames.json}.
     * Held in a side store so they automatically re-overlay on top of
     * {@link #SELECTORS} every time the datapack-layer selector listener
     * fires — without needing the resource manager to guarantee reload
     * ordering.
     */
    private static final Map<ResourceLocation, NameSelector> USER_SELECTOR_BACKING = new LinkedHashMap<>();

    private NameRegistry() {}

    public static SimpleJsonResourceReloadListener poolListener()     { return new PoolListener(); }
    public static SimplePreparableReloadListener<ChainPrepResult> chainListener() { return new ChainListener(); }
    public static SimpleJsonResourceReloadListener selectorListener() { return new SelectorListener(); }
    public static SimpleJsonResourceReloadListener configListener()   { return new ConfigListener(); }

    public static synchronized Optional<NamePool> pool(ResourceLocation id) {
        return Optional.ofNullable(POOLS.get(id));
    }

    public static synchronized Optional<NameChain> chain(ResourceLocation id) {
        return Optional.ofNullable(CHAINS.get(id));
    }

    /**
     * Dev-mode overlay — chains the user has saved via the in-game editor
     * this session. We keep them in a side map so they can be re-applied
     * after every {@link #replaceChains} (which re-derives chains from the
     * classpath / dev jar — those have stale base-mod data because Loom
     * doesn't propagate {@code common/src/main/resources/} edits into the
     * dev jar at runtime).
     */
    private static final Map<ResourceLocation, NameChain> CHAIN_OVERLAY = new LinkedHashMap<>();

    /**
     * Synchronously overwrite one chain in the in-memory registry and pin
     * it as a session overlay so subsequent {@code /reload}-style refreshes
     * don't revert it. Used by the dev-mode datapack editor so a saved
     * chain stays visible across screens and server reloads.
     */
    public static synchronized void putChainInMemory(NameChain chain) {
        if (chain == null) return;
        CHAINS.put(chain.id(), chain);
        CHAIN_OVERLAY.put(chain.id(), chain);
    }

    /** Drop a chain from the dev overlay (its next reload-derived form will stick). */
    public static synchronized void clearChainOverlay(ResourceLocation id) {
        CHAIN_OVERLAY.remove(id);
    }

    /** Immutable view of every registered pool — keyed by id, insertion-order preserved. */
    public static synchronized Map<ResourceLocation, NamePool> allPools() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(POOLS));
    }

    public static synchronized Map<ResourceLocation, NameChain> allChains() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(CHAINS));
    }

    public static synchronized Map<ResourceLocation, NameSelector> allSelectors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SELECTORS));
    }

    public static synchronized String packIdOfPool(ResourceLocation id) {
        return POOL_PACKS.getOrDefault(id, UNKNOWN_PACK);
    }

    public static synchronized String packIdOfChain(ResourceLocation id) {
        return CHAIN_PACKS.getOrDefault(id, UNKNOWN_PACK);
    }

    /**
     * Every pack id that contributed a layer to {@code id}'s merged
     * chain, low-priority first. Includes every pack — both ones that
     * shipped the base layer and ones that appended via {@code "replace": false}.
     * Returns a single-element list with the winning pack when only one
     * pack contributed, or an empty list when the chain isn't registered.
     */
    public static synchronized List<String> packsOfChain(ResourceLocation id) {
        List<String> layers = CHAIN_PACK_LAYERS.get(id);
        if (layers == null) {
            String single = CHAIN_PACKS.get(id);
            return single == null ? List.of() : List.of(single);
        }
        return List.copyOf(layers);
    }

    public static synchronized String packIdOfSelector(ResourceLocation id) {
        return SELECTOR_PACKS.getOrDefault(id, UNKNOWN_PACK);
    }

    /** True when the selector was contributed by the user-config file, not a datapack. */
    public static synchronized boolean isUserSelector(ResourceLocation id) {
        return id != null && USER_SELECTOR_BACKING.containsKey(id);
    }

    /** Snapshot of every user-defined selector. Insertion-ordered. */
    public static synchronized Map<ResourceLocation, NameSelector> userSelectors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(USER_SELECTOR_BACKING));
    }

    /**
     * Replace the user-selector overlay with {@code next} and overlay it on
     * top of the current {@link #SELECTORS} map. Previously-installed user
     * selector ids not in {@code next} are removed from {@link #SELECTORS}
     * (unless a datapack selector with the same id exists, in which case
     * the datapack version stays). Datapack-id collisions log a warning;
     * the shipped/datapack selector wins.
     */
    public static synchronized void installUserSelectors(Map<ResourceLocation, NameSelector> next) {
        Set<ResourceLocation> previous = new LinkedHashSet<>(USER_SELECTOR_BACKING.keySet());
        USER_SELECTOR_BACKING.clear();
        if (next != null) USER_SELECTOR_BACKING.putAll(next);

        // Drop previous user selectors that aren't carrying over.
        for (ResourceLocation id : previous) {
            if (!USER_SELECTOR_BACKING.containsKey(id)) {
                SELECTORS.remove(id);
                SELECTOR_PACKS.remove(id);
            }
        }
        // Overlay (skip ids that collide with datapack selectors).
        for (Map.Entry<ResourceLocation, NameSelector> e : USER_SELECTOR_BACKING.entrySet()) {
            ResourceLocation id = e.getKey();
            if (SELECTORS.containsKey(id) && !SELECTOR_PACKS.getOrDefault(id, USER_PACK).equals(USER_PACK)) {
                LOGGER.warn("[AdventureItemNames] user selector {} collides with datapack selector — keeping datapack version", id);
                continue;
            }
            SELECTORS.put(id, e.getValue());
            SELECTOR_PACKS.put(id, USER_PACK);
        }
    }

    /**
     * First selector whose {@code applies_to} tag is on {@code stack}, or
     * empty when no registered selector covers the stack's item type. When
     * several selectors could match a single item, the first declared
     * wins — control order by file name (alphabetical within a namespace).
     */
    public static synchronized Optional<NameSelector> findMatching(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        for (NameSelector sel : SELECTORS.values()) {
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, sel.appliesTo());
            if (stack.is(tagKey)) return Optional.of(sel);
        }
        return Optional.empty();
    }

    private static synchronized void replacePools(Map<ResourceLocation, NamePool> next,
                                                  Map<ResourceLocation, String> packs) {
        POOLS.clear();
        POOLS.putAll(next);
        POOL_PACKS.clear();
        POOL_PACKS.putAll(packs);
        LOGGER.info("[AdventureItemNames] pools reloaded — {}", POOLS.size());
    }

    private static synchronized void replaceChains(Map<ResourceLocation, NameChain> next,
                                                   Map<ResourceLocation, String> packs,
                                                   Map<ResourceLocation, List<String>> packLayers) {
        CHAINS.clear();
        CHAINS.putAll(next);
        // Re-apply session overlay so saved in-game edits survive the reload.
        // The dev jar/build classpath holds the pre-edit base mod data, so
        // a vanilla reload would otherwise revert chains the user just saved.
        for (Map.Entry<ResourceLocation, NameChain> e : CHAIN_OVERLAY.entrySet()) {
            CHAINS.put(e.getKey(), e.getValue());
        }
        CHAIN_PACKS.clear();
        CHAIN_PACKS.putAll(packs);
        CHAIN_PACK_LAYERS.clear();
        if (packLayers != null) CHAIN_PACK_LAYERS.putAll(packLayers);
        LOGGER.info("[AdventureItemNames] chains reloaded — {} ({} overlay)",
            CHAINS.size(), CHAIN_OVERLAY.size());
    }

    private static synchronized void replaceSelectors(Map<ResourceLocation, NameSelector> next,
                                                      Map<ResourceLocation, String> packs) {
        SELECTORS.clear();
        SELECTORS.putAll(next);
        SELECTOR_PACKS.clear();
        SELECTOR_PACKS.putAll(packs);
        // Re-overlay user-defined selectors so they survive a datapack reload
        // regardless of whether ConfigListener has fired yet for this reload cycle.
        for (Map.Entry<ResourceLocation, NameSelector> e : USER_SELECTOR_BACKING.entrySet()) {
            ResourceLocation id = e.getKey();
            if (SELECTORS.containsKey(id)) continue; // datapack wins
            SELECTORS.put(id, e.getValue());
            SELECTOR_PACKS.put(id, USER_PACK);
        }
        LOGGER.info("[AdventureItemNames] selectors reloaded — {} ({} user)",
            SELECTORS.size(), USER_SELECTOR_BACKING.size());
    }

    /**
     * Resolve which loaded pack contributes {@code fileId}. Returns
     * {@link #UNKNOWN_PACK} on lookup failure — vanilla resource manager
     * may report empty if the resource was filtered post-prepare or if
     * pack-source bookkeeping was lost during reload teardown.
     */
    private static String resolvePackId(ResourceManager mgr, ResourceLocation fileId, String subpath) {
        ResourceLocation diskPath = ResourceLocation.fromNamespaceAndPath(
            fileId.getNamespace(), "naming/" + subpath + "/" + fileId.getPath() + ".json");
        return mgr.getResource(diskPath).map(Resource::sourcePackId).orElse(UNKNOWN_PACK);
    }

    private static final class PoolListener extends SimpleJsonResourceReloadListener {
        PoolListener() { super(GSON, "naming/pools"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NamePool> out = new LinkedHashMap<>();
            Map<ResourceLocation, String> packs = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NamePool pool = NameCodec.parsePool(e.getValue(), id);
                    out.put(pool.id(), pool);
                    packs.put(pool.id(), resolvePackId(mgr, fileId, "pools"));
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] pool {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replacePools(out, packs);
        }
    }

    /** Background-thread preparation output for the chain listener — one entry per chain id, layers ordered low-priority → high-priority. */
    public record ChainPrepResult(Map<ResourceLocation, List<ChainLayer>> layersById) {}

    /** One contributing pack's view of a chain id, captured during prepare. */
    public record ChainLayer(NameChain chain, String packId) {}

    /**
     * Two-phase chain reloader. In {@link #prepare} we walk
     * {@code data/&lt;ns&gt;/naming/chains/*.json} and use
     * {@link ResourceManager#getResourceStack} to gather <strong>every</strong>
     * layer that contributes a given chain id (vanilla returns
     * lowest-priority pack first → highest-priority pack last). In
     * {@link #apply} we collapse each layer stack via {@link #mergeLayers}:
     * a {@code "replace": true} layer resets the merge state for that id,
     * a {@code "replace": false} layer appends its {@code refs} lists to
     * the current merge by segment index.
     */
    private static final class ChainListener extends SimplePreparableReloadListener<ChainPrepResult> {

        @Override
        protected ChainPrepResult prepare(ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, List<ChainLayer>> layersById = new LinkedHashMap<>();
            Map<ResourceLocation, Resource> top = mgr.listResources(
                CHAIN_DIR, rl -> rl.getPath().endsWith(CHAIN_EXT));
            for (ResourceLocation filePath : top.keySet()) {
                ResourceLocation chainId = chainIdFromFilePath(filePath);
                if (chainId == null) continue;
                List<Resource> stack = mgr.getResourceStack(filePath);
                List<ChainLayer> layers = new ArrayList<>(stack.size());
                for (Resource r : stack) {
                    try (InputStream in = r.open();
                         Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        JsonElement root = com.google.gson.JsonParser.parseReader(reader);
                        NameChain chain = NameCodec.parseChain(root, chainId);
                        layers.add(new ChainLayer(chain, r.sourcePackId()));
                    } catch (Exception ex) {
                        LOGGER.error("[AdventureItemNames] chain layer {} from pack {} failed to parse — {}",
                                     filePath, r.sourcePackId(), ex.getMessage());
                    }
                }
                if (!layers.isEmpty()) layersById.put(chainId, layers);
            }
            return new ChainPrepResult(layersById);
        }

        @Override
        protected void apply(ChainPrepResult prep, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NameChain> out = new LinkedHashMap<>();
            Map<ResourceLocation, String> packs = new LinkedHashMap<>();
            Map<ResourceLocation, List<String>> packLayers = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, List<ChainLayer>> e : prep.layersById().entrySet()) {
                ResourceLocation chainId = e.getKey();
                List<ChainLayer> layers = e.getValue();
                NameChain merged = mergeLayers(layers);
                out.put(chainId, merged);
                packs.put(chainId, layers.get(layers.size() - 1).packId());
                // Distinct, order-preserving pack ids — `"replace": true` resets
                // the visible-contributors list so a hard override doesn't keep
                // tagging the chain with packs whose data was discarded.
                List<String> contributors = new ArrayList<>(layers.size());
                for (ChainLayer layer : layers) {
                    if (layer.chain().replace()) contributors.clear();
                    if (contributors.isEmpty() || !contributors.get(contributors.size() - 1).equals(layer.packId())) {
                        contributors.add(layer.packId());
                    }
                }
                packLayers.put(chainId, contributors);
            }
            replaceChains(out, packs, packLayers);
        }
    }

    /**
     * Strip the {@code naming/chains/} prefix and {@code .json} suffix from
     * a resource file path to recover the chain id. Returns {@code null}
     * for paths that don't fit the expected shape — those are silently
     * skipped during prepare.
     */
    private static ResourceLocation chainIdFromFilePath(ResourceLocation filePath) {
        String path = filePath.getPath();
        String prefix = CHAIN_DIR + "/";
        if (!path.startsWith(prefix) || !path.endsWith(CHAIN_EXT)) return null;
        String chainPath = path.substring(prefix.length(), path.length() - CHAIN_EXT.length());
        if (chainPath.isEmpty()) return null;
        return ResourceLocation.fromNamespaceAndPath(filePath.getNamespace(), chainPath);
    }

    /**
     * Collapse an ordered layer list (low-priority → high-priority) into
     * one {@link NameChain}. {@code replace: true} resets the merge state;
     * {@code replace: false} appends per-segment refs onto the current
     * merge. Returns the last layer untouched when no merge happens.
     */
    static NameChain mergeLayers(List<ChainLayer> layers) {
        NameChain merged = null;
        for (ChainLayer layer : layers) {
            NameChain c = layer.chain();
            if (merged == null || c.replace()) {
                merged = c;
            } else {
                merged = appendLayer(merged, c);
            }
        }
        return merged;
    }

    /**
     * Append {@code add}'s segments onto {@code base} by segment index.
     * Segment metadata ({@code chance}, {@code connection}, {@code newline})
     * is taken from {@code base} for overlapping segments — additive layers
     * contribute refs only. Segments beyond {@code base}'s count are taken
     * wholesale from {@code add}.
     */
    private static NameChain appendLayer(NameChain base, NameChain add) {
        List<NameSegment> baseSegs = base.segments();
        List<NameSegment> addSegs = add.segments();
        List<NameSegment> merged = new ArrayList<>(Math.max(baseSegs.size(), addSegs.size()));
        for (int i = 0; i < baseSegs.size(); i++) {
            NameSegment b = baseSegs.get(i);
            if (i < addSegs.size()) {
                NameSegment a = addSegs.get(i);
                List<NameSegment.WeightedRef> mergedRefs = new ArrayList<>(b.refs().size() + a.refs().size());
                mergedRefs.addAll(b.refs());
                mergedRefs.addAll(a.refs());
                // Label always carried from base — themed packs don't author labels.
                merged.add(new NameSegment(List.copyOf(mergedRefs), b.chance(), b.connection(), b.newline(), b.label()));
            } else {
                merged.add(b);
            }
        }
        for (int i = baseSegs.size(); i < addSegs.size(); i++) {
            merged.add(addSegs.get(i));
        }
        return new NameChain(base.id(), List.copyOf(merged), base.replace());
    }

    private static final class SelectorListener extends SimpleJsonResourceReloadListener {
        SelectorListener() { super(GSON, "naming/selectors"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NameSelector> out = new LinkedHashMap<>();
            Map<ResourceLocation, String> packs = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NameSelector sel = NameCodec.parseSelector(e.getValue(), id);
                    out.put(sel.id(), sel);
                    packs.put(sel.id(), resolvePackId(mgr, fileId, "selectors"));
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] selector {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replaceSelectors(out, packs);
        }
    }
}
