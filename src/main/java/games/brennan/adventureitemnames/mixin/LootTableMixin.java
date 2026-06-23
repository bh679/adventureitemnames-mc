package games.brennan.adventureitemnames.mixin;

import games.brennan.adventureitemnames.api.NameComposer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inject into vanilla {@link LootTable#getRandomItems(LootContext)} so
 * every item rolled by any loot path (chests, mob drops, fishing,
 * archaeology, …) flows through
 * {@link NameComposer#applyName(ItemStack, net.minecraft.util.RandomSource)}.
 *
 * <p>This replaces the NeoForge-only Global Loot Modifier from v0.1.0
 * with a cross-loader implementation. The mixin lives in {@code common/}
 * and is bundled identically by the Fabric, Forge, and NeoForge jars.</p>
 *
 * <p>The composer is a no-op for items whose tags don't match any
 * registered selector, so the per-roll overhead on non-namable items
 * (food, blocks, etc.) is bounded by selector lookup.</p>
 */
@Mixin(LootTable.class)
public abstract class LootTableMixin {

    @Inject(
        method = "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
        at = @At("RETURN")
    )
    private void adventureitemnames$applyNames(LootContext ctx, CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir) {
        ObjectArrayList<ItemStack> list = cir.getReturnValue();
        if (list == null || list.isEmpty()) return;
        for (ItemStack stack : list) {
            NameComposer.applyName(stack, ctx.getRandom());
        }
    }
}
