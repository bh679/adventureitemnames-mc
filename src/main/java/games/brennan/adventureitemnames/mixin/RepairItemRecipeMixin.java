package games.brennan.adventureitemnames.mixin;

import games.brennan.adventureitemnames.compat.ItemNameCompat;
import games.brennan.adventureitemnames.internal.RepairWinner;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.21.1 {
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.CraftingInput;
//?} else {
/*import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.CraftingContainer;
*///?}

/**
 * Keeps a named item's name and lore through crafting-grid repair.
 *
 * <p>Vanilla {@link RepairItemRecipe#assemble} builds a <em>fresh</em> result
 * stack carrying only the combined durability and merged enchantments, so a
 * named item came out of a repair nameless. This injects at {@code RETURN} and
 * copies the winning input's custom name and lore onto the result.
 *
 * <p>The winner is chosen by position — <em>left, then higher</em> (see
 * {@link RepairWinner}), the same rule AdventureItemStats uses for stats, so
 * name and stats come from the same item. Position is authoritative: an unnamed
 * item in the winning slot gives an unnamed result. {@code CraftingResultMixin}
 * skips the "crafted by" description for a repair, so the result reads as the
 * same item, mended. The anvil and grindstone already copy their left/top input
 * and are untouched.
 */
@Mixin(RepairItemRecipe.class)
public abstract class RepairItemRecipeMixin {

    //? if >=1.21.1 {
    @Inject(
        method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN")
    )
    private void adventureitemnames$keepRepairName(CraftingInput input, HolderLookup.Provider registries,
                                                   CallbackInfoReturnable<ItemStack> cir) {
        int size = input.size();
        ItemStack[] grid = new ItemStack[size];
        for (int i = 0; i < size; i++) grid[i] = input.getItem(i);
        adventureitemnames$copyFromWinner(grid, input.width(), cir.getReturnValue());
    }
    //?} else {
    /*@Inject(
        method = "assemble(Lnet/minecraft/world/inventory/CraftingContainer;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN")
    )
    private void adventureitemnames$keepRepairName(CraftingContainer input, RegistryAccess registries,
                                                   CallbackInfoReturnable<ItemStack> cir) {
        int size = input.getContainerSize();
        ItemStack[] grid = new ItemStack[size];
        for (int i = 0; i < size; i++) grid[i] = input.getItem(i);
        adventureitemnames$copyFromWinner(grid, input.getWidth(), cir.getReturnValue());
    }*///?}

    @Unique
    private static void adventureitemnames$copyFromWinner(ItemStack[] grid, int width, ItemStack result) {
        if (result == null || result.isEmpty()) return;
        boolean[] occupied = new boolean[grid.length];
        for (int i = 0; i < grid.length; i++) occupied[i] = !grid[i].isEmpty();
        int winner = RepairWinner.winningIndex(occupied, width);
        if (winner < 0) return;
        ItemNameCompat.copyNameAndLore(grid[winner], result);
    }
}
