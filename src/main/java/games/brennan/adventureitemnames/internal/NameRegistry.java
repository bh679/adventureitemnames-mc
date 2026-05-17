package games.brennan.adventureitemnames.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

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
 */
public final class NameRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final Map<ResourceLocation, NamePool> POOLS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameChain> CHAINS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, NameSelector> SELECTORS = new LinkedHashMap<>();

    private NameRegistry() {}

    /** Hook from {@code AddReloadListenerEvent}. Registers three sibling listeners. */
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PoolListener());
        event.addListener(new ChainListener());
        event.addListener(new SelectorListener());
    }

    public static synchronized Optional<NamePool> pool(ResourceLocation id) {
        return Optional.ofNullable(POOLS.get(id));
    }

    public static synchronized Optional<NameChain> chain(ResourceLocation id) {
        return Optional.ofNullable(CHAINS.get(id));
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

    private static synchronized void replacePools(Map<ResourceLocation, NamePool> next) {
        POOLS.clear();
        POOLS.putAll(next);
        LOGGER.info("[AdventureItemNames] pools reloaded — {}", POOLS.size());
    }

    private static synchronized void replaceChains(Map<ResourceLocation, NameChain> next) {
        CHAINS.clear();
        CHAINS.putAll(next);
        LOGGER.info("[AdventureItemNames] chains reloaded — {}", CHAINS.size());
    }

    private static synchronized void replaceSelectors(Map<ResourceLocation, NameSelector> next) {
        SELECTORS.clear();
        SELECTORS.putAll(next);
        LOGGER.info("[AdventureItemNames] selectors reloaded — {}", SELECTORS.size());
    }

    private static final class PoolListener extends SimpleJsonResourceReloadListener {
        PoolListener() { super(GSON, "naming/pools"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NamePool> out = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NamePool pool = NameCodec.parsePool(e.getValue(), id);
                    out.put(pool.id(), pool);
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] pool {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replacePools(out);
        }
    }

    private static final class ChainListener extends SimpleJsonResourceReloadListener {
        ChainListener() { super(GSON, "naming/chains"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NameChain> out = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NameChain chain = NameCodec.parseChain(e.getValue(), id);
                    out.put(chain.id(), chain);
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] chain {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replaceChains(out);
        }
    }

    private static final class SelectorListener extends SimpleJsonResourceReloadListener {
        SelectorListener() { super(GSON, "naming/selectors"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
            Map<ResourceLocation, NameSelector> out = new LinkedHashMap<>();
            for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
                ResourceLocation fileId = e.getKey();
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    fileId.getNamespace(), fileId.getPath());
                try {
                    NameSelector sel = NameCodec.parseSelector(e.getValue(), id);
                    out.put(sel.id(), sel);
                } catch (NameCodec.NameParseException ex) {
                    LOGGER.error("[AdventureItemNames] selector {} failed to parse — {}", fileId, ex.getMessage());
                }
            }
            replaceSelectors(out);
        }
    }
}
