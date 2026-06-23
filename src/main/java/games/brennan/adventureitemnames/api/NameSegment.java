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
 * <p>Each weighted choice can point at either a {@link NamePool} or
 * another {@link NameChain} so multi-level composition ("Title Prefix +
 * of + Title Combinations") stays expressible in JSON.</p>
 */
public record NameSegment(
    List<WeightedRef> refs,
    float chance,
    String connection,
    boolean newline,
    String label
) {
    /** Legacy 4-arg constructor — call sites that pre-date the label field default it to empty. */
    public NameSegment(List<WeightedRef> refs, float chance, String connection, boolean newline) {
        this(refs, chance, connection, newline, "");
    }

    public record WeightedRef(ResourceLocation ref, float weight) {}
}
