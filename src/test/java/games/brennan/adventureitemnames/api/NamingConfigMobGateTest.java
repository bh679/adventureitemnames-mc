package games.brennan.adventureitemnames.api;

import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the mob-name gate registry on {@link NamingConfig} — the veto hook a
 * host mod (e.g. Dungeon Train) registers to restrict AIN's ambient mob naming
 * to a subset of entities (e.g. mobs on the train).
 *
 * <p>The gates registered here ignore their {@link Mob} argument, so the
 * registry and AND-merge logic can be exercised with a {@code null} mob — no
 * bootstrapped Minecraft registry or live entity required (mirrors
 * {@code NameComposerPlayerMobTest} / {@code NamingConfigWeightTest}).
 */
class NamingConfigMobGateTest {

    private static final Predicate<Mob> ALLOW = m -> true;
    private static final Predicate<Mob> DENY = m -> false;

    @BeforeEach
    @AfterEach
    void resetGates() {
        // MOB_NAME_GATES is process-global static state — isolate each test and
        // never leak a gate into other test classes.
        NamingConfig.clearMobNameGates();
    }

    @Test
    void noGateRegistered_allowsByDefault() {
        // Standalone default: AIN names everything when no host gate exists.
        assertTrue(NamingConfig.isMobNameAllowed(null));
    }

    @Test
    void singleDenyGate_vetoes() {
        NamingConfig.registerMobNameGate(DENY);
        assertFalse(NamingConfig.isMobNameAllowed(null));
    }

    @Test
    void singleAllowGate_permits() {
        NamingConfig.registerMobNameGate(ALLOW);
        assertTrue(NamingConfig.isMobNameAllowed(null));
    }

    @Test
    void multipleGates_andSemantics() {
        // Eligible only if EVERY gate permits — one deny vetoes the whole set.
        NamingConfig.registerMobNameGate(ALLOW);
        NamingConfig.registerMobNameGate(DENY);
        assertFalse(NamingConfig.isMobNameAllowed(null));
    }

    @Test
    void multipleAllowGates_permit() {
        NamingConfig.registerMobNameGate(ALLOW);
        NamingConfig.registerMobNameGate(ALLOW);
        assertTrue(NamingConfig.isMobNameAllowed(null));
    }

    @Test
    void nullGate_isIgnored() {
        NamingConfig.registerMobNameGate(null);
        assertTrue(NamingConfig.isMobNameAllowed(null));
    }

    @Test
    void clearRemovesGates() {
        NamingConfig.registerMobNameGate(DENY);
        assertFalse(NamingConfig.isMobNameAllowed(null));
        NamingConfig.clearMobNameGates();
        assertTrue(NamingConfig.isMobNameAllowed(null));
    }
}
