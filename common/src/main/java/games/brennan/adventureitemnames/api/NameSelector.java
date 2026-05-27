package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Maps an item tag (e.g. {@code minecraft:swords}) to a per-tier
 * {@link NameChain} id for both the item's display name and its lore
 * description. The composer iterates registered selectors and fires the
 * first whose {@link #appliesTo()} tag matches the candidate
 * {@code ItemStack}.
 *
 * <p>{@link #tiers()} keys correspond to {@link NameTier#name()} lowercased
 * and resolve to the {@code CUSTOM_NAME} chain. {@link #descriptionTiers()}
 * keys use the same convention but resolve to a chain whose output is split
 * on {@code \n} and applied as appended {@code DataComponents.LORE} lines.
 * Description tiers are optional — an empty map yields no lore changes.
 * Future item kinds (tools, shields, armor) add their own selector JSON
 * without any Java change.</p>
 */
public record NameSelector(
    ResourceLocation id,
    ResourceLocation appliesTo,
    Map<String, ResourceLocation> tiers,
    Map<String, ResourceLocation> descriptionTiers
) {
    public NameSelector {
        if (descriptionTiers == null) descriptionTiers = Map.of();
    }

    public NameSelector(ResourceLocation id, ResourceLocation appliesTo,
                        Map<String, ResourceLocation> tiers) {
        this(id, appliesTo, tiers, Map.of());
    }
}
