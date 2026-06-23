package games.brennan.adventureitemnames.api;

import games.brennan.adventureitemnames.compat.Ids;
import games.brennan.adventureitemnames.internal.ChanceOverrides;
import games.brennan.adventureitemnames.internal.DisableSet;
import games.brennan.adventureitemnames.internal.EntryOverrides;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import games.brennan.adventureitemnames.internal.SelectorOverrides;
import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Precedence + clamping checks for
 * {@link NamingConfig#effectiveWeight(ResourceLocation, int, ResourceLocation, float)}.
 * Verifies the API → user-config → shipped fall-through and that
 * negative overrides clamp to zero (treated as a "suppress" marker).
 *
 * <p>Resets the API layer after each test so cross-test contamination
 * doesn't bleed through the singleton {@code NamingConfig} state.
 */
class NamingConfigWeightTest {

    private static final ResourceLocation CHAIN =
        Ids.of("adventureitemnames", "title_combinations");
    private static final ResourceLocation REF =
        Ids.of("adventureitemnames", "food");

    @AfterEach
    void resetLayers() {
        NamingConfig.restoreApiLayer(new NamingConfig.ApiSnapshot(
            new DisableSet(),
            new WeightOverrides(),
            new EntryOverrides(),
            new ChanceOverrides(),
            new SelectorOverrides(),
            new SegmentOverrides()));
        NamingConfig.setUserWeightOverrides(new WeightOverrides());
        NamingConfig.setUserEntryOverrides(new EntryOverrides());
        NamingConfig.setUserChances(new ChanceOverrides());
        NamingConfig.setUserSelectorOverrides(new SelectorOverrides());
        NamingConfig.setUserSegmentOverrides(new SegmentOverrides());
    }

    @Test
    void shippedFallsThroughWithNoOverrides() {
        assertEquals(0.10f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
    }

    @Test
    void userOverrideBeatsShipped() {
        WeightOverrides user = new WeightOverrides();
        user.weights.put(WeightOverrides.key(CHAIN, 1, REF), 0.50f);
        NamingConfig.setUserWeightOverrides(user);
        assertEquals(0.50f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
    }

    @Test
    void apiOverrideBeatsUser() {
        WeightOverrides user = new WeightOverrides();
        user.weights.put(WeightOverrides.key(CHAIN, 1, REF), 0.50f);
        NamingConfig.setUserWeightOverrides(user);
        NamingConfig.overrideWeight(CHAIN, 1, REF, 0.25f);
        assertEquals(0.25f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
    }

    @Test
    void apiOverrideClearedFallsThroughToUser() {
        WeightOverrides user = new WeightOverrides();
        user.weights.put(WeightOverrides.key(CHAIN, 1, REF), 0.50f);
        NamingConfig.setUserWeightOverrides(user);
        NamingConfig.overrideWeight(CHAIN, 1, REF, 0.25f);
        NamingConfig.clearWeightOverride(CHAIN, 1, REF);
        assertEquals(0.50f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
    }

    @Test
    void negativeOverrideClampsToZero() {
        NamingConfig.overrideWeight(CHAIN, 1, REF, -42f);
        // overrideWeight with a negative value actually clears the override per its
        // contract — so the shipped value comes through.
        assertEquals(0.10f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);

        // A user-layer negative — emulate via direct WeightOverrides — should clamp
        // to zero in effectiveWeight.
        WeightOverrides user = new WeightOverrides();
        user.weights.put(WeightOverrides.key(CHAIN, 1, REF), -1.0f);
        NamingConfig.setUserWeightOverrides(user);
        assertEquals(0f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
    }

    @Test
    void nullArgsDoNotThrow() {
        assertEquals(0.5f, NamingConfig.effectiveWeight(null, 0, REF, 0.5f), 1e-6f);
        assertEquals(0.5f, NamingConfig.effectiveWeight(CHAIN, 0, null, 0.5f), 1e-6f);
    }

    @Test
    void snapshotRestoreRoundtrip() {
        NamingConfig.overrideWeight(CHAIN, 1, REF, 0.99f);
        NamingConfig.disablePool(REF);
        NamingConfig.ApiSnapshot snap = NamingConfig.snapshotApiLayer();

        NamingConfig.overrideWeight(CHAIN, 1, REF, 0.01f);
        NamingConfig.enablePool(REF);
        assertEquals(0.01f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
        assertTrue(NamingConfig.isPoolEnabled(REF));

        NamingConfig.restoreApiLayer(snap);
        assertEquals(0.99f, NamingConfig.effectiveWeight(CHAIN, 1, REF, 0.10f), 1e-6f);
        assertFalse(NamingConfig.isPoolEnabled(REF));
    }
}
