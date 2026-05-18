package games.brennan.adventureitemnames.api;

import java.util.List;

/**
 * One declarative mutation applied by a {@link NameChainExtension} against
 * a target {@link NameChain}. Two flavours in v1:
 *
 * <ul>
 *   <li>{@link AddRefs} — append weighted refs to an existing segment's
 *       {@code refs[]}. Order-independent across extensions.</li>
 *   <li>{@link AddSegment} — insert a whole new segment at a position.
 *       Order matters; {@code ChainMerger} sorts extensions by ID for
 *       determinism.</li>
 * </ul>
 *
 * <p>Destructive ops (remove/replace/set-chance) are deliberately
 * out-of-scope for v1 to keep merge semantics conflict-free. Packs that
 * truly need destructive control can still override the whole chain
 * file.</p>
 */
public sealed interface NameChainOp permits NameChainOp.AddRefs, NameChainOp.AddSegment {

    /**
     * Identifies a segment within a target chain. Named lookup is the
     * recommended form — index targeting breaks silently when the base
     * mod reorders segments.
     */
    sealed interface SegmentRef permits SegmentRef.ByName, SegmentRef.ByIndex {
        record ByName(String name) implements SegmentRef {}
        record ByIndex(int index) implements SegmentRef {}
    }

    /**
     * Where an {@link AddSegment} inserts its new segment. {@code Start}
     * pushes existing segments back; {@code End} appends; {@code At(i)}
     * inserts so the new segment ends up at index {@code i} in the
     * post-op chain.
     */
    sealed interface InsertPos permits InsertPos.Start, InsertPos.End, InsertPos.At {
        record Start() implements InsertPos {}
        record End() implements InsertPos {}
        record At(int index) implements InsertPos {}
    }

    record AddRefs(SegmentRef target, List<NameSegment.WeightedRef> refs) implements NameChainOp {}

    record AddSegment(InsertPos at, NameSegment segment) implements NameChainOp {}
}
