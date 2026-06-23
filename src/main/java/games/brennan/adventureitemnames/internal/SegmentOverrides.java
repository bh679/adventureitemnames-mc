package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-segment override snapshot for one layer (user-config / API).
 * Sibling of {@link WeightOverrides} and {@link SelectorOverrides}, but
 * carries every segment-level edit a v3 chain editor can stage:
 * {@code chance}, {@code connection}, {@code newline}, full ref-list
 * replacement, removal flag, and appended-segments list per chain.
 *
 * <p>Per-segment edits ({@link #edits}) are keyed by
 * {@code <chain_id>#<segment_index>}. Any {@link SegmentEdit} field may be
 * {@code null} — meaning "no override; fall through". A non-null
 * {@code refs} list <em>replaces</em> the shipped ref list wholesale;
 * per-ref weights still flow through {@link WeightOverrides}. A
 * {@code removed=true} edit causes the composer to skip the segment
 * entirely.
 *
 * <p>{@link #appendedSegments} carries brand-new segments appended to a
 * chain by the GUI. The effective segment count for a chain is
 * {@code shipped.size() + appendedSegments.get(chainId).size()}; the
 * effective index range is {@code [0, shippedSize)} mapping to shipped
 * segments and {@code [shippedSize, total)} mapping into the appended
 * list. Per-segment edits work for both ranges uniformly.
 *
 * <p>Not thread-safe — callers hold the {@code NamingConfig} lock.
 */
public final class SegmentOverrides {

    public final Map<String, SegmentEdit> edits = new HashMap<>();
    /** chainId.toString() → ordered list of newly appended segments. */
    public final Map<String, List<NameSegment>> appendedSegments = new LinkedHashMap<>();
    /**
     * chainId.toString() → display order as a permutation of the
     * effective-index range. Entry at position {@code i} carries the
     * <em>original</em> segment index that should appear at display
     * position {@code i}. Original indices in {@code [0, shippedCount)}
     * refer to shipped segments; {@code [shippedCount, total)} refer to
     * appended segments by their position in {@link #appendedSegments}.
     */
    public final Map<String, List<Integer>> segmentOrder = new LinkedHashMap<>();

    /**
     * One segment's pending field overrides. Any field may be {@code null}
     * to leave that aspect unchanged. {@code refs} {@code null} means the
     * shipped ref list is used; a non-null (possibly empty) list replaces
     * it entirely. {@code removed=true} drops the segment from the chain.
     */
    public record SegmentEdit(
        Float chance,
        String connection,
        Boolean newline,
        List<NameSegment.WeightedRef> refs,
        Boolean removed,
        String label
    ) {
        public boolean isNoOp() {
            return chance == null && connection == null && newline == null
                && refs == null && (removed == null || !removed) && label == null;
        }

        public SegmentEdit withChance(Float v)             { return new SegmentEdit(v, connection, newline, refs, removed, label); }
        public SegmentEdit withConnection(String v)        { return new SegmentEdit(chance, v, newline, refs, removed, label); }
        public SegmentEdit withNewline(Boolean v)          { return new SegmentEdit(chance, connection, v, refs, removed, label); }
        public SegmentEdit withRefs(List<NameSegment.WeightedRef> v) {
            return new SegmentEdit(chance, connection, newline, v, removed, label);
        }
        public SegmentEdit withRemoved(Boolean v)          { return new SegmentEdit(chance, connection, newline, refs, v, label); }
        public SegmentEdit withLabel(String v)             { return new SegmentEdit(chance, connection, newline, refs, removed, v); }

        public static SegmentEdit empty() { return new SegmentEdit(null, null, null, null, null, null); }
    }

    public static String key(ResourceLocation chainId, int segmentIndex) {
        return chainId + "#" + segmentIndex;
    }

    public void clear() {
        edits.clear();
        appendedSegments.clear();
        segmentOrder.clear();
    }

    public void mergeFrom(SegmentOverrides other) {
        if (other == null) return;
        edits.putAll(other.edits);
        for (var e : other.appendedSegments.entrySet()) {
            appendedSegments.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        for (var e : other.segmentOrder.entrySet()) {
            segmentOrder.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
    }

    public boolean isEmpty() {
        return edits.isEmpty() && appendedSegments.isEmpty() && segmentOrder.isEmpty();
    }

    public Map<String, List<Integer>> snapshotOrder() {
        Map<String, List<Integer>> out = new LinkedHashMap<>(segmentOrder.size());
        for (var e : segmentOrder.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    /** Get the order list for {@code chainId}, or {@code null} if no reorder is set. */
    public List<Integer> orderOf(ResourceLocation chainId) {
        return segmentOrder.get(chainId.toString());
    }

    public Map<String, SegmentEdit> snapshot() {
        return new LinkedHashMap<>(edits);
    }

    public Map<String, List<NameSegment>> snapshotAppended() {
        Map<String, List<NameSegment>> out = new LinkedHashMap<>(appendedSegments.size());
        for (var e : appendedSegments.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    /** Number of segments appended to the chain's shipped list. */
    public int appendedCount(ResourceLocation chainId) {
        List<NameSegment> list = appendedSegments.get(chainId.toString());
        return list == null ? 0 : list.size();
    }

    /** Read one appended segment by its 0-based index into the appended list (NOT the overall chain index). */
    public NameSegment appendedAt(ResourceLocation chainId, int appendedIndex) {
        List<NameSegment> list = appendedSegments.get(chainId.toString());
        if (list == null || appendedIndex < 0 || appendedIndex >= list.size()) return null;
        return list.get(appendedIndex);
    }
}
