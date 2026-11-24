package games.brennan.adventureitemnames.api;

import games.brennan.adventureitemnames.compat.Ids;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composer's rule roll: skip_if sees segments before and after, chance_if decides at a
 * segment's turn from earlier picks, and chains without rules draw nothing up front.
 */
class RuleRollTest {
    private static final ResourceLocation CHAIN = Ids.of("adventureitemnames", "rule_roll_test");
    private static final ResourceLocation SUPPORTERS = Ids.of("adventureitemnames", "discord_people");
    private static final ResourceLocation BACKERS = Ids.of("dungeontrain", "backers");
    private static final ResourceLocation NAMES = Ids.of("adventureitemnames", "names");
    private static final List<Integer> ORDER_3 = List.of(0, 1, 2);

    private static NameSegment seg(float chance, List<Integer> skipIf, List<NameSegment.ChanceRule> chanceIf) {
        return new NameSegment(List.of(), chance, " ", false, "", false, skipIf, chanceIf);
    }

    private static NameSegment seg(float chance, List<Integer> skipIf) {
        return seg(chance, skipIf, List.of());
    }

    private static NameSegment.Condition picks(int segment, ResourceLocation... refs) {
        return new NameSegment.Condition(segment, List.of(refs));
    }

    @AfterEach
    void clearApiLayer() {
        for (int i = 0; i < 4; i++) NamingConfig.clearSegmentOverride(CHAIN, i);
    }

    /** Settle each segment in order, recording {@code firstPick} for segment 0. Returns which appeared. */
    private static boolean[] play(RuleRoll roll, List<NameSegment> segs, ResourceLocation firstPick) {
        boolean[] out = new boolean[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            out[i] = roll.settle(i, segs.get(i));
            if (out[i] && i == 0) roll.recordPick(0, firstPick);
        }
        return out;
    }

    @Test
    void chainWithoutRulesDrawsNothing() {
        List<NameSegment> segs = List.of(seg(0.5f, List.of()), seg(0.5f, List.of()));
        RandomSource rng = RandomSource.create(42);
        RandomSource twin = RandomSource.create(42);
        assertNull(RuleRoll.begin(CHAIN, List.of(0, 1), segs, rng));
        assertEquals(twin.nextFloat(), rng.nextFloat());
    }

    @Test
    void laterSegmentFiringSkipsEarlierOne() {
        // 0 Name, 1 Fan (skip if 2), 2 closing Title: all certain.
        List<NameSegment> segs = List.of(seg(1f, List.of()), seg(1f, List.of(2)), seg(1f, List.of()));
        boolean[] out = play(RuleRoll.begin(CHAIN, ORDER_3, segs, RandomSource.create(1)), segs, NAMES);
        assertTrue(out[0]);
        assertFalse(out[1]);
        assertTrue(out[2]);
    }

    @Test
    void ruleStaysQuietWhenTickedSegmentMisses() {
        List<NameSegment> segs = List.of(seg(0f, List.of()), seg(1f, List.of(0, 2)), seg(0f, List.of()));
        boolean[] out = play(RuleRoll.begin(CHAIN, ORDER_3, segs, RandomSource.create(7)), segs, NAMES);
        assertTrue(out[1]);
    }

    @Test
    void selfReferenceNeverSkips() {
        List<NameSegment> segs = List.of(seg(1f, List.of(0)));
        assertTrue(play(RuleRoll.begin(CHAIN, List.of(0), segs, RandomSource.create(1)), segs, NAMES)[0]);
    }

    @Test
    void chanceRuleUsesEarlierPick() {
        // 0 First Name (certain), 1 Fan: 0% normally, 100% after a supporter name.
        var rule = new NameSegment.ChanceRule(List.of(picks(0, SUPPORTERS, BACKERS)), 1f);
        List<NameSegment> segs = List.of(seg(1f, List.of()), seg(0f, List.of(), List.of(rule)));
        assertTrue(play(RuleRoll.begin(CHAIN, List.of(0, 1), segs, RandomSource.create(3)), segs, BACKERS)[1]);
        assertFalse(play(RuleRoll.begin(CHAIN, List.of(0, 1), segs, RandomSource.create(3)), segs, NAMES)[1]);
    }

    @Test
    void firstMatchingRuleWinsAndLaterSegmentsNeverCount() {
        var mentionsLater = new NameSegment.ChanceRule(List.of(new NameSegment.Condition(2, List.of())), 1f);
        var fallback = new NameSegment.ChanceRule(List.of(new NameSegment.Condition(0, List.of())), 0f);
        List<NameSegment> segs = List.of(
            seg(1f, List.of()),
            seg(1f, List.of(), List.of(mentionsLater, fallback)),
            seg(1f, List.of()));
        RuleRoll roll = RuleRoll.begin(CHAIN, ORDER_3, segs, RandomSource.create(5));
        assertNotNull(roll);
        // Segment 2 has not had its turn, so the first rule can't hold; the second does (0%).
        assertFalse(play(roll, segs, NAMES)[1]);
    }

    @Test
    void supporterTagSplitMatchesPlan() {
        // playermob_name shape: 0 First Name, 1 Fan (0.2 / 0.5 for supporters), 2 Suffix
        // (0.3 / 0.04 if supporter + Fan / 0.8 if supporter).
        var sup = picks(0, SUPPORTERS, BACKERS);
        NameSegment first = seg(1f, List.of());
        NameSegment fan = seg(0.2f, List.of(), List.of(new NameSegment.ChanceRule(List.of(sup), 0.5f)));
        NameSegment suffix = seg(0.3f, List.of(), List.of(
            new NameSegment.ChanceRule(List.of(sup, new NameSegment.Condition(1, List.of())), 0.04f),
            new NameSegment.ChanceRule(List.of(sup), 0.8f)));
        List<NameSegment> segs = List.of(first, fan, suffix);
        RandomSource rng = RandomSource.create(2026);
        int n = 100_000, fanOnly = 0, suffixOnly = 0, both = 0, plainFan = 0, plainSuffix = 0;
        for (int i = 0; i < n; i++) {
            boolean[] s = play(RuleRoll.begin(CHAIN, ORDER_3, segs, rng), segs, SUPPORTERS);
            if (s[1] && s[2]) both++; else if (s[1]) fanOnly++; else if (s[2]) suffixOnly++;
            boolean[] p = play(RuleRoll.begin(CHAIN, ORDER_3, segs, rng), segs, NAMES);
            if (p[1]) plainFan++;
            if (p[2]) plainSuffix++;
        }
        assertEquals(0.48, fanOnly / (double) n, 0.01);
        assertEquals(0.40, suffixOnly / (double) n, 0.01);
        assertEquals(0.02, both / (double) n, 0.005);
        assertEquals(0.20, plainFan / (double) n, 0.01);
        assertEquals(0.30, plainSuffix / (double) n, 0.01);
    }

    @Test
    void overrideReplacesShippedSkipRuleAndEmptyClearsIt() {
        List<NameSegment> segs = List.of(seg(1f, List.of()), seg(1f, List.of(0)));
        assertFalse(play(RuleRoll.begin(CHAIN, List.of(0, 1), segs, RandomSource.create(1)), segs, NAMES)[1]);
        NamingConfig.overrideSegment(CHAIN, 1, SegmentOverrides.SegmentEdit.empty().withSkipIf(List.of()));
        assertEquals(List.of(), NamingConfig.effectiveSegmentSkipIf(CHAIN, 1, segs.get(1).skipIf()));
        // No rule left anywhere: the chain rolls the plain way again.
        assertNull(RuleRoll.begin(CHAIN, List.of(0, 1), segs, RandomSource.create(1)));
    }

    @Test
    void ruleAddedOnlyByOverrideStillApplies() {
        List<NameSegment> plain = List.of(seg(1f, List.of()), seg(1f, List.of()));
        NamingConfig.overrideSegmentSkipIf(CHAIN, 0, List.of(1));
        boolean[] out = play(RuleRoll.begin(CHAIN, List.of(0, 1), plain, RandomSource.create(3)), plain, NAMES);
        assertFalse(out[0]);
        assertTrue(out[1]);
    }

    @Test
    void overrideSegmentSkipIfKeepsOtherApiFields() {
        var refs = List.of(new NameSegment.WeightedRef(NAMES, 1f));
        NamingConfig.overrideSegment(CHAIN, 2, SegmentOverrides.SegmentEdit.empty().withRefs(refs));
        NamingConfig.overrideSegmentSkipIf(CHAIN, 2, List.of(0));
        assertEquals(refs, NamingConfig.effectiveSegmentRefs(CHAIN, 2, List.of()));
        assertEquals(List.of(0), NamingConfig.effectiveSegmentSkipIf(CHAIN, 2, List.of()));
    }
}
