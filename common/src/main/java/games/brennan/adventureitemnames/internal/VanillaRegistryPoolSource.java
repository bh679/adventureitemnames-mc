package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic pool source that exposes every vanilla block and item as two
 * pools:
 * <ul>
 *   <li>{@code adventureitemnames:mc_blocks} — every {@code minecraft:}
 *       block's display name (e.g. {@code "Cobblestone"}, {@code "Crafting Table"})</li>
 *   <li>{@code adventureitemnames:mc_items} — every {@code minecraft:}
 *       item's display name (e.g. {@code "Diamond Sword"}, {@code "Music Disc"})</li>
 * </ul>
 *
 * <p>Filters to the vanilla {@code minecraft:} namespace so modded
 * blocks/items don't pollute the {@code mc_names} pack. Skips
 * {@code minecraft:air} — the empty-string name would corrupt assembled
 * mob names with stray spaces.</p>
 *
 * <p>Display names are resolved via {@link Block#getName()} and
 * {@link Item#getDescription()}, both of which return a translatable
 * {@link net.minecraft.network.chat.Component}. Server-side the resolution
 * is always {@code en_US}; client-side preview (title-screen config) uses
 * the player's chosen language. The mismatch is harmless — names you see
 * in the preview are illustrative, and the actual rolled name on a loot
 * drop is whatever the integrated/dedicated server resolved.</p>
 *
 * <p>One instance can serve every loader because the implementation only
 * touches loader-neutral vanilla APIs. Each loader's mod-init registers
 * one instance via
 * {@link NameRegistry#registerSyntheticPoolSource(String, SyntheticPoolSource)}.</p>
 */
public final class VanillaRegistryPoolSource implements SyntheticPoolSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String VANILLA_NS = "minecraft";
    private static final String AIR_PATH = "air";

    /** Pool id for every vanilla block name. */
    public static final ResourceLocation MC_BLOCKS =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "mc_blocks");

    /** Pool id for every vanilla item name. */
    public static final ResourceLocation MC_ITEMS =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "mc_items");

    @Override
    public Map<ResourceLocation, NamePool> produce() {
        Map<ResourceLocation, NamePool> out = new LinkedHashMap<>(2);
        out.put(MC_BLOCKS, buildBlockPool());
        out.put(MC_ITEMS, buildItemPool());
        return out;
    }

    private static NamePool buildBlockPool() {
        List<NamePool.PoolEntry> entries = new ArrayList<>(BuiltInRegistries.BLOCK.size());
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = e.getKey().location();
            if (!includeId(id)) continue;
            String name = e.getValue().getName().getString();
            if (name == null || name.isEmpty()) continue;
            entries.add(NamePool.PoolEntry.universal(name));
        }
        if (entries.isEmpty()) {
            LOGGER.warn("[AdventureItemNames] mc_blocks synthetic pool is empty — BuiltInRegistries.BLOCK had no usable entries");
        } else {
            LOGGER.info("[AdventureItemNames] mc_blocks synthetic pool — {} entries", entries.size());
        }
        return new NamePool(MC_BLOCKS, List.copyOf(entries));
    }

    private static NamePool buildItemPool() {
        List<NamePool.PoolEntry> entries = new ArrayList<>(BuiltInRegistries.ITEM.size());
        for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> e : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = e.getKey().location();
            if (!includeId(id)) continue;
            String name = e.getValue().getDescription().getString();
            if (name == null || name.isEmpty()) continue;
            entries.add(NamePool.PoolEntry.universal(name));
        }
        if (entries.isEmpty()) {
            LOGGER.warn("[AdventureItemNames] mc_items synthetic pool is empty — BuiltInRegistries.ITEM had no usable entries");
        } else {
            LOGGER.info("[AdventureItemNames] mc_items synthetic pool — {} entries", entries.size());
        }
        return new NamePool(MC_ITEMS, List.copyOf(entries));
    }

    /**
     * Keep vanilla, drop {@code minecraft:air}. Modded blocks/items live in
     * other namespaces and don't belong in the {@code mc_names} pack — they'd
     * also leak the names of unrelated mods into rolled item names.
     */
    private static boolean includeId(ResourceLocation id) {
        if (!VANILLA_NS.equals(id.getNamespace())) return false;
        if (AIR_PATH.equals(id.getPath())) return false;
        return true;
    }
}
