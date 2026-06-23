package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NameComposer#isPlayerMobId(ResourceLocation)} — the
 * string-based, dependency-free detection used to route PlayerMob mobs
 * ({@code playermob:player_mob}) to the {@code playermob_name} chain.
 *
 * <p>Pure {@link ResourceLocation} comparison, so it runs without a
 * bootstrapped Minecraft registry (mirrors {@code NamingConfigWeightTest}).
 */
class NameComposerPlayerMobTest {

    @Test
    void matchesPlayerMobEntityId() {
        assertTrue(NameComposer.isPlayerMobId(
            ResourceLocation.fromNamespaceAndPath("playermob", "player_mob")));
    }

    @Test
    void rejectsOtherEntityIds() {
        // Different mod, different path, and a vanilla mob all fail to match.
        assertFalse(NameComposer.isPlayerMobId(
            ResourceLocation.fromNamespaceAndPath("minecraft", "zombie")));
        assertFalse(NameComposer.isPlayerMobId(
            ResourceLocation.fromNamespaceAndPath("playermob", "player_mob_spawn_egg")));
        assertFalse(NameComposer.isPlayerMobId(
            ResourceLocation.fromNamespaceAndPath("minecraft", "player_mob")));
    }

    @Test
    void nullIdIsNotPlayerMob() {
        // BuiltInRegistries.ENTITY_TYPE.getKey can theoretically return null.
        assertFalse(NameComposer.isPlayerMobId(null));
    }
}
