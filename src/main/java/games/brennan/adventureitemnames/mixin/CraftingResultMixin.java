package games.brennan.adventureitemnames.mixin;

import games.brennan.adventureitemnames.api.NameComposer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.ArmorDyeRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.1 {
import net.minecraft.world.inventory.RecipeCraftingHolder;
//?} else {
/*import net.minecraft.world.inventory.RecipeHolder;
*///?}

/**
 * Inject into vanilla {@link ResultSlot#onTake(Player, ItemStack)} so every
 * item the player takes out of a crafting result slot (the 2x2 inventory
 * grid or the 3x3 crafting table) flows through
 * {@link NameComposer#applyCraftedDescription(ItemStack, net.minecraft.util.RandomSource, Player)}.
 *
 * <p>{@code ResultSlot} is the canonical crafting-result slot for vanilla
 * recipes — smithing, anvil, brewing, and furnace results use different
 * slot classes and are intentionally <em>not</em> covered by this mixin.</p>
 *
 * <p>A crafting-grid repair ({@link RepairItemRecipe}) is skipped: the result is
 * the same item, mended, and keeps its own name and lore via
 * {@code RepairItemRecipeMixin}, so it gets no new "crafted by" line.</p>
 *
 * <p>Server-side only — gated by {@code instanceof ServerPlayer} so the
 * client never mutates the stack (client mutations get overwritten by the
 * subsequent server sync). Mirrors the server-scope of {@code MobSpawnMixin}.
 * Lives in {@code common/} and bundled identically by the Fabric, Forge,
 * and NeoForge jars.</p>
 */
@Mixin(ResultSlot.class)
public abstract class CraftingResultMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void adventureitemnames$describeCrafted(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer)) return;
        Recipe<?> used = adventureitemnames$recipeUsed();
        if (used instanceof RepairItemRecipe) return;
        if (used instanceof ArmorDyeRecipe) {
            NameComposer.applyDyedDescription(stack, player.level().getRandom(), player);
            return;
        }
        NameComposer.applyCraftedDescription(stack, player.level().getRandom(), player);
    }

    /** The recipe behind the result slot's current output, or null when the container doesn't track one. */
    @Unique
    private Recipe<?> adventureitemnames$recipeUsed() {
        //? if >=1.21.1 {
        if (!(((Slot) (Object) this).container instanceof RecipeCraftingHolder holder)) return null;
        var used = holder.getRecipeUsed();
        return used == null ? null : used.value();
        //?} else {
        /*if (!(((Slot) (Object) this).container instanceof RecipeHolder holder)) return null;
        return holder.getRecipeUsed();
        *///?}
    }
}
