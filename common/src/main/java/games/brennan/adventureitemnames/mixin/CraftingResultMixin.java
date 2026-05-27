package games.brennan.adventureitemnames.mixin;

import games.brennan.adventureitemnames.api.NameComposer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inject into vanilla {@link ResultSlot#onTake(Player, ItemStack)} so every
 * item the player takes out of a crafting result slot (the 2x2 inventory
 * grid or the 3x3 crafting table) flows through
 * {@link NameComposer#applyCraftedName(ItemStack, net.minecraft.util.RandomSource, Player)}.
 *
 * <p>{@code ResultSlot} is the canonical crafting-result slot for vanilla
 * recipes — smithing, anvil, brewing, and furnace results use different
 * slot classes and are intentionally <em>not</em> covered by this mixin.</p>
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
    private void adventureitemnames$nameCrafted(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer)) return;
        NameComposer.applyCraftedName(stack, player.level().getRandom(), player);
    }
}
