package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One link in a {@link NameChain} — picks a weighted pool/chain ref, fires
 * with probability {@code chance}, and prepends {@code connection}
 * (optionally with a leading newline) when it does.
 *
 * <p>{@code label} is an authoring aid — empty string means "no custom
 * label, fall back to {@code Seg <index>}". The composer ignores it.
 * Persisted on the shipped JSON so labels survive into committed
 * datapacks.
 *
 * <p>{@code link} marks a joining segment (e.g. a vowel between two name
 * endings): when it fires, its fragment is held back and only emitted if the
 * next segment in the same chain fires too, the text so far ends in a
 * consonant, and that next fragment starts with one (a, e, i, o, u and y count
 * as vowels). Otherwise it is dropped, so a link never makes a double vowel.
 *
 * <p>{@code skipIf} lists indices of other segments in the same chain: when
 * any of them fires (its chance roll passes) in a roll, this segment is
 * skipped, wherever it sits in the order. Empty means no rule. Indices, not
 * labels, because labels can repeat within a chain.</p>
 *
 * <p>{@code chanceIf} holds chance rules checked top to bottom: the first rule
 * whose conditions all hold replaces {@code chance} for that roll. A condition
 * is "segment N fired", optionally "and picked one of these refs"; only
 * segments earlier in the roll order count. Empty means the plain chance.</p>
 *
 * <p>Each weighted choice can point at either a {@link NamePool} or
 * another {@link NameChain} so multi-level composition ("Title Prefix +
 * of + Title Combinations") stays expressible in JSON.</p>
 */
public record NameSegment(
    List<WeightedRef> refs,
    float chance,
    String connection,
    boolean newline,
    String label,
    boolean link,
    List<Integer> skipIf,
    List<ChanceRule> chanceIf
) {
    public NameSegment {
        skipIf = skipIf == null ? List.of() : List.copyOf(skipIf);
        chanceIf = chanceIf == null ? List.of() : List.copyOf(chanceIf);
    }

    /** Constructor without chance rules. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline,
                       String label, boolean link, List<Integer> skipIf) {
        this(refs, chance, connection, newline, label, link, skipIf, List.of());
    }

    /** Legacy 4-arg constructor — call sites that pre-date the label field default it to empty. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline) {
        this(refs, chance, connection, newline, "", false, List.of());
    }

    /** Legacy 5-arg constructor — call sites that pre-date the link field default it to false. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline, String label) {
        this(refs, chance, connection, newline, label, false, List.of());
    }

    /** Legacy 6-arg constructor — call sites that pre-date the skip-if field default it to no rule. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline,
                       String label, boolean link) {
        this(refs, chance, connection, newline, label, link, List.of());
    }

    /** This segment with {@code link} set — for copies that rebuild a segment field by field. */
    public NameSegment withLink(boolean link) {
        return new NameSegment(refs, chance, connection, newline, label, link, skipIf, chanceIf);
    }

    /** This segment with {@code skipIf} set — for copies that rebuild a segment field by field. */
    public NameSegment withSkipIf(List<Integer> skipIf) {
        return new NameSegment(refs, chance, connection, newline, label, link, skipIf, chanceIf);
    }

    /** This segment with {@code chanceIf} set — for copies that rebuild a segment field by field. */
    public NameSegment withChanceIf(List<ChanceRule> chanceIf) {
        return new NameSegment(refs, chance, connection, newline, label, link, skipIf, chanceIf);
    }

    public record WeightedRef(ResourceLocation ref, float weight) {}

    /** One chance rule: when every condition holds, the segment rolls at {@code chance}. */
    public record ChanceRule(List<Condition> when, float chance) {
        public ChanceRule {
            when = when == null ? List.of() : List.copyOf(when);
            chance = Math.max(0f, Math.min(1f, chance));
        }
    }

    /**
     * Segment {@code segment} fired in this roll; when {@code refs} is non-empty it must also
     * have picked one of those refs.
     */
    public record Condition(int segment, List<ResourceLocation> refs) {
        public Condition {
            refs = refs == null ? List.of() : List.copyOf(refs);
        }
    }
}
