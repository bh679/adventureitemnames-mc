package games.brennan.adventureitemnames.api;

import games.brennan.adventureitemnames.compat.Ids;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composer's skip_if handling: rules pre-roll every chance, chains without rules roll
 * nothing up front (so their names are unchanged), and a ticked segment that fires, before or
 * after, skips the ruled one.
 */
class NameComposerSkipIfTest {
    private static final ResourceLocation CHAIN = Ids.of("adventureitemnames", "skip_if_compose_test");

    private static NameSegment seg(float chance, List<Integer> skipIf) {
        return new NameSegment(List.of(), chance, " ", false, "", false, skipIf);
    }

    @AfterEach
    void clearApiLayer() {
        for (int i = 0; i < 4; i++) NamingConfig.clearSegmentOverride(CHAIN, i);
    }

    @Test
    void chainWithoutRulesRollsNothingUpFront() {
        List<NameSegment> segs = List.of(seg(0.5f, List.of()), seg(0.5f, List.of()));
        RandomSource rng = RandomSource.create(42);
        RandomSource twin = RandomSource.create(42);
        assertNull(NameComposer.preRollIfRuled(CHAIN, List.of(0, 1), segs, rng));
        // The RNG was not advanced, so the inline rolls stay exactly as before rules existed.
        assertEquals(twin.nextFloat(), rng.nextFloat());
    }

    @Test
    void laterTitleFiringSkipsEarlierFan() {
        // 0 Name (always), 1 Fan (always, skip if 2), 2 closing Title (always).
        List<NameSegment> segs = List.of(seg(1f, List.of()), seg(1f, List.of(2)), seg(1f, List.of()));
        Set<Integer> fired = NameComposer.preRollIfRuled(CHAIN, List.of(0, 1, 2), segs, RandomSource.create(1));
        assertEquals(Set.of(0, 1, 2), fired);
        assertTrue(NameComposer.skippedByRule(CHAIN, 1, segs.get(1), fired));
        assertFalse(NameComposer.skippedByRule(CHAIN, 0, segs.get(0), fired));
    }

    @Test
    void fanStaysWhenNoTickedSegmentFires() {
        List<NameSegment> segs = List.of(seg(0f, List.of()), seg(1f, List.of(0, 2)), seg(0f, List.of()));
        Set<Integer> fired = NameComposer.preRollIfRuled(CHAIN, List.of(0, 1, 2), segs, RandomSource.create(7));
        assertEquals(Set.of(1), fired);
        assertFalse(NameComposer.skippedByRule(CHAIN, 1, segs.get(1), fired));
    }

    @Test
    void selfReferenceNeverSkips() {
        NameSegment self = seg(1f, List.of(0));
        assertFalse(NameComposer.skippedByRule(CHAIN, 0, self, Set.of(0)));
    }

    @Test
    void overrideReplacesShippedRuleAndEmptyClearsIt() {
        List<NameSegment> segs = List.of(seg(1f, List.of()), seg(1f, List.of(0)));
        Set<Integer> fired = Set.of(0, 1);
        assertTrue(NameComposer.skippedByRule(CHAIN, 1, segs.get(1), fired));

        NamingConfig.overrideSegment(CHAIN, 1, SegmentOverrides.SegmentEdit.empty().withSkipIf(List.of()));
        assertEquals(List.of(), NamingConfig.effectiveSegmentSkipIf(CHAIN, 1, segs.get(1).skipIf()));
        assertFalse(NameComposer.skippedByRule(CHAIN, 1, segs.get(1), fired));

        // A rule added only by override also makes the chain pre-roll.
        List<NameSegment> plain = List.of(seg(1f, List.of()), seg(1f, List.of()));
        NamingConfig.overrideSegmentSkipIf(CHAIN, 0, List.of(1));
        assertEquals(Set.of(0, 1), NameComposer.preRollIfRuled(CHAIN, List.of(0, 1), plain, RandomSource.create(3)));
    }

    @Test
    void overrideSegmentSkipIfKeepsOtherApiFields() {
        var refs = List.of(new NameSegment.WeightedRef(Ids.of("adventureitemnames", "names"), 1f));
        NamingConfig.overrideSegment(CHAIN, 2, SegmentOverrides.SegmentEdit.empty().withRefs(refs));
        NamingConfig.overrideSegmentSkipIf(CHAIN, 2, List.of(0));
        assertEquals(refs, NamingConfig.effectiveSegmentRefs(CHAIN, 2, List.of()));
        assertEquals(List.of(0), NamingConfig.effectiveSegmentSkipIf(CHAIN, 2, List.of()));
    }
}
