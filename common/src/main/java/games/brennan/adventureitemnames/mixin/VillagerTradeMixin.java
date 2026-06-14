package games.brennan.adventureitemnames.mixin;

import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inject into {@link AbstractVillager#addOffersFromItemListings} — the shared
 * offer-creation path used by both {@code Villager} and {@code WanderingTrader}
 * when they roll new trades — so every purchasable item flows through
 * {@link NameComposer#applyVillagerTradeNaming(net.minecraft.world.item.ItemStack, String, RandomSource)}.
 *
 * <p>Only the offers added by <em>this</em> call are processed: the offer-list
 * size is captured at {@code HEAD} and the tail is walked at {@code RETURN}.
 * That keeps the hook idempotent across villager level-ups (which append new
 * offers without touching old ones) and across NBT reloads (which restore
 * already-named offers and never call this method).</p>
 *
 * <p>Server-side only — gated by {@code !level().isClientSide()}, mirroring the
 * scope of {@code CraftingResultMixin} / {@code MobSpawnMixin}. Lives in
 * {@code common/} and is bundled identically by Fabric, Forge, and NeoForge.</p>
 */
@Mixin(AbstractVillager.class)
public abstract class VillagerTradeMixin {

    @Unique
    private int adventureitemnames$offerCountBefore;

    @Inject(method = "addOffersFromItemListings", at = @At("HEAD"))
    private void adventureitemnames$captureOfferCount(MerchantOffers offers, VillagerTrades.ItemListing[] listings,
                                                      int count, CallbackInfo ci) {
        this.adventureitemnames$offerCountBefore = offers.size();
    }

    @Inject(method = "addOffersFromItemListings", at = @At("RETURN"))
    private void adventureitemnames$nameNewOffers(MerchantOffers offers, VillagerTrades.ItemListing[] listings,
                                                  int count, CallbackInfo ci) {
        AbstractVillager self = (AbstractVillager) (Object) this;
        if (self.level().isClientSide()) return;

        RandomSource rng = self.getRandom();
        String villagerName = self.getName().getString();
        float stockLimitChance = NamingConfig.chanceTradeStockLimit();

        for (int i = this.adventureitemnames$offerCountBefore; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            boolean named = NameComposer.applyVillagerTradeNaming(offer.getResult(), villagerName, rng);
            if (named && rng.nextFloat() < stockLimitChance) {
                ((MerchantOfferAccessor) offer).adventureitemnames$setMaxUses(1);
            }
        }
    }
}
