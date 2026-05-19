package games.brennan.adventureitemnames.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameChainExtension;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 * {@link #extensionListener()}, {@link #selectorListener()} to obtain
 * listener instances and registers them via the loader's native
 * resource-manager API (NeoForge: {@code AddReloadListenerEvent}; Forge:
 * {@code AddReloadListenerEvent}; Fabric: {@code ResourceManagerHelper}).</p>
 *
 * <p>Chains and chain-extensions are reloaded independently, so the
 * {@link #MERGED_CHAINS} cache is rebuilt by whichever of the two
 * listeners fires — final state is identical regardless of order.
 * {@link #chain(ResourceLocation)} always returns from the merged cache,
 * not the raw {@link #CHAINS} map.</p>
 */
public final class NameRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final Map<ResourceLocation, NamePool> POOLS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChain> CHAINS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChainExtension> EXTENSIONS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChain> MERGED_CHAINS = new LinkedHashMap<>();
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
    private static final Map<ResourceLocation, String> SELECTOR_PACKS = new LinkedHashMap<>();

    /** Returned by the {@code packIdOf*} lookups when the resource manager couldn't pin a source. */
    public static final String UNKNOWN_PACK = "unknown";

    private NameRegistry() {}

    public static SimpleJsonResourceReloadListener poolListener()      { return new PoolListener(); }
    public static SimpleJsonResourceReloadListener chainListener()     { return new ChainListener(); }
    public static SimpleJsonResourceReloadListener extensionListener() { return new ExtensionListener(); }
    public static SimpleJsonResourceReloadListener selectorListener()  { return new SelectorListener(); }
    public static SimpleJsonResourceReloadListener configListener()    { return new ConfigListener(); }

    public static synchronized Optional<NamePool> pool(ResourceLocation id) {
        return Optional.ofNullable(POOLS.get(id));
    }

    public static synchronized Optional<NameChain> chain(ResourceLocation id) {
        return Optional.ofNullable(MERGED_CHAINS.get(id));
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

    public static synchronized String packIdOfSelector(ResourceLocation id) {
        return SELECTOR_PACKS.getOrDefault(id, UNKNOWN_PACK);
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
                                                   Map<ResourceLocation, String> packs) {
        CHAINS.clear();
        CHAINS.putAll(next);
        CHAIN_PACKS.clear();
        CHAIN_PACKS.putAll(packs);
        rebuildMergedChains();
        LOGGER.info("[AdventureItemNames] chains reloaded — {}", CHAINS.size());
    }

    private static synchronized void replaceExtensions(Map<ResourceLocation, NameChainExtension> next) {
        EXTENSIONS.clear();
        EXTENSIONS.putAll(next);
        rebuildMergedChains();
        LOGGER.info("[AdventureItemNames] chain extensions reloaded — {}", EXTENSIONS.size());
    }

    private static void rebuildMergedChains() {
        MERGED_CHAINS.clear();
        MERGED_CHAINS.putAll(ChainMerger.merge(CHAINS, EXTENSIONS));
    }

    private static synchronized void replaceSelectors(Map<ResourceLocation, NameSelector> next,
                                                      Map<ResourceLocation, String> packs) {
        SELECTORS.clear();
        SELECTORS.putAll(next);
        SELECTOR_PACKS.clear();
        SELECTOR_PACKS.putAll(packs);
        LOGGER.info("[AdventureItemNames] selectors reloaded — {}", SELECTORS.size());
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

    private static final class ChainListener extends SimpleJsonResourceReloadListener {
        ChainListener() { super(GSON, "naming/chains"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NameChain> out = new LinkedHashMap<>();
            Map<ResourceLocation, String> packs = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NameChain chain = NameCodec.parseChain(e.getValue(), id);
                    out.put(chain.id(), chain);
                    packs.put(chain.id(), resolvePackId(mgr, fileId, "chains"));
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] chain {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replaceChains(out, packs);
        }
    }

    private static final class ExtensionListener extends SimpleJsonResourceReloadListener {
        ExtensionListener() { super(GSON, "naming/chain_extensions"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NameChainExtension> out = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NameChainExtension ext = NameCodec.parseChainExtension(e.getValue(), id);
                    out.put(ext.id(), ext);
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] chain extension {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replaceExtensions(out);
        }
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
