package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One link in a {@link NameChain} — picks a weighted pool/chain ref, fires
 * with probability {@code chance}, and prepends {@code connection}
 * (optionally with a leading newline) when it does.
 *
 * <p>Each weighted choice can point at either a {@link NamePool} or
 * another {@link NameChain} so multi-level composition ("Title Prefix +
 * of + Title Combinations") stays expressible in JSON.</p>
 *
 * <p>{@code name} is an optional stable identifier used by
 * {@code data/<ns>/naming/chain_extensions/} entries to target this
 * segment regardless of its position in the chain. Empty string means
 * anonymous — extensions can still target it by integer index.</p>
 */
public record NameSegment(
    String name,
    List<WeightedRef> refs,
    float chance,
    String connection,
    boolean newline
) {
    public record WeightedRef(ResourceLocation ref, float weight) {}
}
