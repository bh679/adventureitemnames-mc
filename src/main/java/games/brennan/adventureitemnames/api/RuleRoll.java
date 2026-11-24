package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One roll of a chain that carries segment rules ({@code skip_if} and/or {@code chance_if}).
 *
 * <p>Every live segment that could miss gets its random draw up front, in roll order, so a
 * segment can see one that comes after it (skip_if) and so a chance decided later (chance_if,
 * after earlier picks are known) still uses the same number of randoms. Before a segment's turn
 * its "fired" state is a guess from its plain chance; at its turn it is settled for real.</p>
 *
 * <p>Chains without rules never build one of these, so they roll exactly as before rules existed.</p>
 */
final class RuleRoll {

    private final ResourceLocation chainId;
    private final Map<Integer, Float> draws = new HashMap<>();
    private final Map<Integer, Boolean> fired = new HashMap<>();
    private final Set<Integer> settled = new HashSet<>();
    private final Map<Integer, ResourceLocation> picks = new HashMap<>();

    private RuleRoll(ResourceLocation chainId) {
        this.chainId = chainId;
    }

    /** Start a roll, or return {@code null} (drawing nothing) when no live segment has a rule. */
    static RuleRoll begin(ResourceLocation chainId, List<Integer> order,
                          List<NameSegment> shippedSegments, RandomSource rng) {
        if (!hasRules(chainId, order, shippedSegments)) return null;
        RuleRoll roll = new RuleRoll(chainId);
        for (int segIdx : order) {
            NameSegment seg = live(chainId, segIdx, shippedSegments);
            if (seg == null) continue;
            float chance = NamingConfig.effectiveSegmentChance(chainId, segIdx, seg.chance());
            if (chance < 1f || !seg.chanceIf().isEmpty()) roll.draws.put(segIdx, rng.nextFloat());
            roll.fired.put(segIdx, roll.passes(segIdx, chance));
        }
        return roll;
    }

    private static boolean hasRules(ResourceLocation chainId, List<Integer> order, List<NameSegment> shippedSegments) {
        for (int segIdx : order) {
            NameSegment seg = live(chainId, segIdx, shippedSegments);
            if (seg == null) continue;
            if (!seg.chanceIf().isEmpty()) return true;
            if (!NamingConfig.effectiveSegmentSkipIf(chainId, segIdx, seg.skipIf()).isEmpty()) return true;
        }
        return false;
    }

    private static NameSegment live(ResourceLocation chainId, int segIdx, List<NameSegment> shippedSegments) {
        if (NamingConfig.isSegmentRemoved(chainId, segIdx)) return null;
        return NamingConfig.effectiveSegmentAt(chainId, segIdx, shippedSegments);
    }

    private boolean passes(int segIdx, float chance) {
        if (chance >= 1f) return true;
        Float draw = draws.get(segIdx);
        return draw != null && draw < chance;
    }

    /**
     * Decide, at its turn, whether {@code seg} appears: its chance (or the first matching chance
     * rule's) against its draw, then its skip_if rule. Records the outcome for later segments.
     */
    boolean settle(int segIdx, NameSegment seg) {
        float chance = chanceFor(segIdx, seg);
        boolean appears = passes(segIdx, chance) && !skipped(segIdx, seg);
        fired.put(segIdx, appears);
        settled.add(segIdx);
        return appears;
    }

    /** Remember which ref this segment picked, for later chance rules that ask. */
    void recordPick(int segIdx, ResourceLocation ref) {
        picks.put(segIdx, ref);
    }

    float chanceFor(int segIdx, NameSegment seg) {
        for (NameSegment.ChanceRule rule : seg.chanceIf()) {
            if (holds(rule)) return rule.chance();
        }
        return NamingConfig.effectiveSegmentChance(chainId, segIdx, seg.chance());
    }

    private boolean holds(NameSegment.ChanceRule rule) {
        for (NameSegment.Condition c : rule.when()) {
            if (!settled.contains(c.segment()) || !fired.getOrDefault(c.segment(), false)) return false;
            if (!c.refs().isEmpty() && !c.refs().contains(picks.get(c.segment()))) return false;
        }
        return true;
    }

    /** True when a segment named in this one's skip_if appears (settled) or is expected to (later). */
    boolean skipped(int segIdx, NameSegment seg) {
        for (int other : NamingConfig.effectiveSegmentSkipIf(chainId, segIdx, seg.skipIf())) {
            if (other != segIdx && fired.getOrDefault(other, false)) return true;
        }
        return false;
    }
}
