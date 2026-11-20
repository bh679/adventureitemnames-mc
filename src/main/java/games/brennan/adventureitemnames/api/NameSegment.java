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
    boolean link
) {
    /** Legacy 4-arg constructor — call sites that pre-date the label field default it to empty. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline) {
        this(refs, chance, connection, newline, "", false);
    }

    /** Legacy 5-arg constructor — call sites that pre-date the link field default it to false. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline, String label) {
        this(refs, chance, connection, newline, label, false);
    }

    /** This segment with {@code link} set — for copies that rebuild a segment field by field. */
    public NameSegment withLink(boolean link) {
        return new NameSegment(refs, chance, connection, newline, label, link);
    }

    public record WeightedRef(ResourceLocation ref, float weight) {}
}
