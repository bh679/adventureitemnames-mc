package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-segment override snapshot for one layer (user-config / API).
 * Sibling of {@link WeightOverrides} and {@link SelectorOverrides}, but
 * carries the four segment-level fields a v3 chain editor can override:
 * {@code chance}, {@code connection}, {@code newline}, and (optionally) a
 * full ref-list replacement.
 *
 * <p>Keys are stable string identifiers in the form
 * {@code <chain_id>#<segment_index>}. Any {@link SegmentEdit} field may be
 * {@code null} — a {@code null} field means "no override; fall through to
 * the next layer". A non-null {@code refs} list <em>replaces</em> the
 * shipped ref list wholesale; per-ref weights still flow through
 * {@link WeightOverrides}.
 *
 * <p>Not thread-safe — callers hold the {@code NamingConfig} lock.
 */
public final class SegmentOverrides {

    public final Map<String, SegmentEdit> edits = new HashMap<>();

    /**
     * One segment's pending field overrides. Any field may be {@code null}
     * to leave that aspect unchanged. {@code refs} {@code null} means the
     * shipped ref list is used; a non-null (possibly empty) list replaces
     * it entirely.
     */
    public record SegmentEdit(
        Float chance,
        String connection,
        Boolean newline,
        List<NameSegment.WeightedRef> refs
    ) {
        public boolean isNoOp() {
            return chance == null && connection == null && newline == null && refs == null;
        }

        public SegmentEdit withChance(Float v)             { return new SegmentEdit(v, connection, newline, refs); }
        public SegmentEdit withConnection(String v)        { return new SegmentEdit(chance, v, newline, refs); }
        public SegmentEdit withNewline(Boolean v)          { return new SegmentEdit(chance, connection, v, refs); }
        public SegmentEdit withRefs(List<NameSegment.WeightedRef> v) {
            return new SegmentEdit(chance, connection, newline, v);
        }

        public static SegmentEdit empty() { return new SegmentEdit(null, null, null, null); }
    }

    public static String key(ResourceLocation chainId, int segmentIndex) {
        return chainId + "#" + segmentIndex;
    }

    public void clear() {
        edits.clear();
    }

    public void mergeFrom(SegmentOverrides other) {
        if (other == null) return;
        edits.putAll(other.edits);
    }

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    public Map<String, SegmentEdit> snapshot() {
        return new LinkedHashMap<>(edits);
    }
}
