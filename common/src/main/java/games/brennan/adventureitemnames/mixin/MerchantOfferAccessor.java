package games.brennan.adventureitemnames.mixin;

import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin exposing {@link MerchantOffer}'s otherwise-unsettable
 * {@code maxUses} field so {@link VillagerTradeMixin} can cap a named trade
 * to a single use ("1 in stock") when the Dungeon Train pack is active.
 *
 * <p>{@code maxUses} is set only in the constructor and has no public setter;
 * {@link Mutable} permits writing it even though it's effectively final.
 * Lives in {@code common/} and is bundled identically by all three loaders.</p>
 */
@Mixin(MerchantOffer.class)
public interface MerchantOfferAccessor {

    @Mutable
    @Accessor("maxUses")
    void adventureitemnames$setMaxUses(int maxUses);
}
