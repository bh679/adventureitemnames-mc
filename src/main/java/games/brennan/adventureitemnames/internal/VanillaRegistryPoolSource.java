package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.compat.Ids;
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
 * <p>Both pools additionally drop any entry whose display name is three
 * or more whitespace-separated words (e.g. {@code "Dark Oak Planks"},
 * {@code "Polished Blackstone Brick Stairs"}, {@code "Music Disc Fragment"}).
 * Long compound names make rolled output unwieldy when chains splice them
 * in mid-phrase; keeping only one- and two-word names gives consistently
 * readable results.</p>
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
        Ids.of("adventureitemnames", "mc_blocks");

    /** Pool id for every vanilla item name. */
    public static final ResourceLocation MC_ITEMS =
        Ids.of("adventureitemnames", "mc_items");

    @Override
    public Map<ResourceLocation, NamePool> produce() {
        Map<ResourceLocation, NamePool> out = new LinkedHashMap<>(2);
        out.put(MC_BLOCKS, buildBlockPool());
        out.put(MC_ITEMS, buildItemPool());
        return out;
    }

    private static NamePool buildBlockPool() {
        int totalConsidered = 0;
        List<NamePool.PoolEntry> entries = new ArrayList<>(BuiltInRegistries.BLOCK.size());
        for (Map.Entry<net.minecraft.resources.ResourceKey<Block>, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = e.getKey().location();
            if (!includeId(id)) continue;
            String name = e.getValue().getName().getString();
            if (name == null || name.isEmpty()) continue;
            totalConsidered++;
            if (!keepByWordCount(name)) continue;
            entries.add(NamePool.PoolEntry.universal(name));
        }
        if (entries.isEmpty()) {
            LOGGER.warn("[AdventureItemNames] mc_blocks synthetic pool is empty — BuiltInRegistries.BLOCK had no usable entries");
        } else {
            LOGGER.info("[AdventureItemNames] mc_blocks synthetic pool — {} of {} entries (filtered names with 3+ words)",
                entries.size(), totalConsidered);
        }
        return new NamePool(MC_BLOCKS, List.copyOf(entries));
    }

    /**
     * Returns {@code true} when {@code name} has fewer than three
     * whitespace-separated tokens. Used by {@link #buildBlockPool()} and
     * {@link #buildItemPool()} to drop long compound names that don't
     * splice cleanly into rolled output. Package-private for unit testing.
     */
    static boolean keepByWordCount(String name) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                words++;
                if (words >= 3) return false;
            }
        }
        return true;
    }

    private static NamePool buildItemPool() {
        int totalConsidered = 0;
        List<NamePool.PoolEntry> entries = new ArrayList<>(BuiltInRegistries.ITEM.size());
        for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> e : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = e.getKey().location();
            if (!includeId(id)) continue;
            String name = e.getValue().getDescription().getString();
            if (name == null || name.isEmpty()) continue;
            totalConsidered++;
            if (!keepByWordCount(name)) continue;
            entries.add(NamePool.PoolEntry.universal(name));
        }
        if (entries.isEmpty()) {
            LOGGER.warn("[AdventureItemNames] mc_items synthetic pool is empty — BuiltInRegistries.ITEM had no usable entries");
        } else {
            LOGGER.info("[AdventureItemNames] mc_items synthetic pool — {} of {} entries (filtered names with 3+ words)",
                entries.size(), totalConsidered);
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
