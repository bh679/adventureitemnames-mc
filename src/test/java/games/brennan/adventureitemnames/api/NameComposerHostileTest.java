package games.brennan.adventureitemnames.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link NameComposer#classify} — which mobs fall into the hostile naming
 * category — and the hostile chance default. Pure booleans, no registry needed.
 */
class NameComposerHostileTest {

    @Test
    void enemyThatIsNotABossIsHostile() {
        // zombie, skeleton, creeper, ghast
        assertEquals(MobCategory.HOSTILE, NameComposer.classify(false, false, false, true, false));
    }

    @Test
    void enemyAnimalIsHostileNotPassive() {
        // hoglin (Animal + Enemy), shulker (AbstractGolem + Enemy)
        assertEquals(MobCategory.HOSTILE, NameComposer.classify(false, false, true, true, false));
    }

    @Test
    void bossesAreNeverNamed() {
        // ender dragon, wither
        assertNull(NameComposer.classify(false, false, false, true, true));
    }

    @Test
    void playerMobStaysPlayerMobThoughHostile() {
        assertEquals(MobCategory.PLAYER_MOB, NameComposer.classify(true, false, false, true, false));
    }

    @Test
    void existingCategoriesUnchanged() {
        assertEquals(MobCategory.VILLAGER, NameComposer.classify(false, true, false, false, false));
        assertEquals(MobCategory.PASSIVE, NameComposer.classify(false, false, true, false, false));
        // a mob that is neither passive nor hostile (e.g. an armor stand-like generic mob)
        assertNull(NameComposer.classify(false, false, false, false, false));
    }

    @Test
    void hostileChanceDefaultsToThreePercent() {
        assertEquals(0.03f, ChanceKind.MOB_HOSTILE.defaultValue(), 1e-6);
        assertEquals(ChanceKind.MOB_HOSTILE, ChanceKind.fromKey("mob_hostile"));
    }
}
