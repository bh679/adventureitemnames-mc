package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Terminal word list — a flat pool of candidate strings keyed by id.
 *
 * <p>Pools never compose further; they sit at the leaves of the
 * {@link NameChain} ref graph. Per-entry {@link PoolEntry#itemTypes()}
 * carries optional vanilla {@code minecraft:&lt;tag&gt;} gates so a word
 * can be restricted to specific item kinds (e.g. {@code "Blade"} →
 * swords only). Default empty = universal across all item types.</p>
 */
public record NamePool(ResourceLocation id, List<PoolEntry> entries) {

    public record PoolEntry(String text, List<ResourceLocation> itemTypes) {
        public static PoolEntry universal(String text) {
            return new PoolEntry(text, List.of());
        }
    }
}
