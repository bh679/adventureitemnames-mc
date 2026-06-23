package games.brennan.adventureitemnames.mixin;

import games.brennan.adventureitemnames.api.NameComposer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inject into vanilla {@link Mob#finalizeSpawn(ServerLevelAccessor,
 * DifficultyInstance, MobSpawnType, SpawnGroupData)} so every fresh
 * mob spawn flows through {@link NameComposer#applyMobName(Mob,
 * net.minecraft.util.RandomSource)}.
 *
 * <p>{@code finalizeSpawn} fires exactly once per fresh spawn and is
 * server-side by virtue of the {@link ServerLevelAccessor} parameter,
 * so chunk-reloaded mobs keep their previously-rolled names instead of
 * re-rolling. Mirrors the {@code LootTableMixin} pattern: lives in
 * {@code common/} and is bundled identically by the Fabric, Forge, and
 * NeoForge jars.</p>
 *
 * <p>{@link NameComposer#applyMobName} is a no-op for mobs outside the
 * villager / passive-mob allowlist and for mobs that already carry a
 * {@code CustomName}, so the per-spawn overhead on hostile mobs is a
 * single {@code instanceof} chain.</p>
 */
@Mixin(Mob.class)
public abstract class MobSpawnMixin {

    @Inject(
        method = "finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;",
        at = @At("RETURN")
    )
    private void adventureitemnames$applyMobName(ServerLevelAccessor level, DifficultyInstance difficulty,
                                                  MobSpawnType reason, SpawnGroupData data,
                                                  CallbackInfoReturnable<SpawnGroupData> cir) {
        NameComposer.applyMobName((Mob) (Object) this, level.getRandom());
    }
}
