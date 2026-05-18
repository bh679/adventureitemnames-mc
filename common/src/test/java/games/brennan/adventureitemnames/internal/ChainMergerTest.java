package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameChainExtension;
import games.brennan.adventureitemnames.api.NameChainOp;
import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for {@link ChainMerger}. Covers add_refs commutativity,
 * named/index segment lookup, missing-target resilience, add_segment
 * placement, and confirms inputs are never mutated.
 */
class ChainMergerTest {

    private static ResourceLocation rl(String s) {
        return ResourceLocation.parse(s);
    }

    private static NameSegment seg(String name, String... refs) {
        List<NameSegment.WeightedRef> refList = new java.util.ArrayList<>();
        for (String r : refs) refList.add(new NameSegment.WeightedRef(rl(r), 1f));
        return new NameSegment(name, List.copyOf(refList), 1f, "", false);
    }

    private static NameChain chain(String id, NameSegment... segments) {
        return new NameChain(rl(id), List.of(segments));
    }

    private static Map<ResourceLocation, NameChain> chains(NameChain... chains) {
        Map<ResourceLocation, NameChain> out = new LinkedHashMap<>();
        for (NameChain c : chains) out.put(c.id(), c);
        return out;
    }

    private static Map<ResourceLocation, NameChainExtension> exts(NameChainExtension... exts) {
        Map<ResourceLocation, NameChainExtension> out = new LinkedHashMap<>();
        for (NameChainExtension e : exts) out.put(e.id(), e);
        return out;
    }

    @Test
    void addRefsByName_appendsToTargetSegment() {
        var raw = chains(chain("test:c1", seg("prefix", "test:base_a"), seg("noun", "test:base_b")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(new NameChainOp.AddRefs(
                new NameChainOp.SegmentRef.ByName("noun"),
                List.of(new NameSegment.WeightedRef(rl("pack:added"), 0.5f))
            ))
        );

        var merged = ChainMerger.merge(raw, exts(ext));

        var noun = merged.get(rl("test:c1")).segments().get(1);
        assertEquals(2, noun.refs().size());
        assertEquals("pack:added", noun.refs().get(1).ref().toString());
        assertEquals(0.5f, noun.refs().get(1).weight(), 0.0001f);
    }

    @Test
    void addRefsByIndex_appendsToCorrectSegment() {
        var raw = chains(chain("test:c1", seg("", "test:a"), seg("", "test:b")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(new NameChainOp.AddRefs(
                new NameChainOp.SegmentRef.ByIndex(0),
                List.of(new NameSegment.WeightedRef(rl("pack:added"), 1f))
            ))
        );

        var merged = ChainMerger.merge(raw, exts(ext));

        assertEquals(2, merged.get(rl("test:c1")).segments().get(0).refs().size());
        assertEquals(1, merged.get(rl("test:c1")).segments().get(1).refs().size());
    }

    @Test
    void twoExtensions_sameSegment_bothApplied_sortedByExtId() {
        var raw = chains(chain("test:c1", seg("noun", "test:base")));
        var extZ = new NameChainExtension(
            rl("pack:zeta"),
            rl("test:c1"),
            List.of(new NameChainOp.AddRefs(
                new NameChainOp.SegmentRef.ByName("noun"),
                List.of(new NameSegment.WeightedRef(rl("pack:z_ref"), 1f))
            ))
        );
        var extA = new NameChainExtension(
            rl("pack:alpha"),
            rl("test:c1"),
            List.of(new NameChainOp.AddRefs(
                new NameChainOp.SegmentRef.ByName("noun"),
                List.of(new NameSegment.WeightedRef(rl("pack:a_ref"), 1f))
            ))
        );

        var merged = ChainMerger.merge(raw, exts(extZ, extA));
        var refs = merged.get(rl("test:c1")).segments().get(0).refs();

        assertEquals(3, refs.size());
        assertEquals("test:base", refs.get(0).ref().toString());
        assertEquals("pack:a_ref", refs.get(1).ref().toString(), "alpha sorts before zeta");
        assertEquals("pack:z_ref", refs.get(2).ref().toString());
    }

    @Test
    void missingTargetChain_extensionSkipped_otherChainsUnaffected() {
        var raw = chains(chain("test:c1", seg("noun", "test:base")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:ghost"),
            List.of(new NameChainOp.AddRefs(
                new NameChainOp.SegmentRef.ByName("noun"),
                List.of(new NameSegment.WeightedRef(rl("pack:added"), 1f))
            ))
        );

        var merged = ChainMerger.merge(raw, exts(ext));

        assertEquals(1, merged.size());
        assertEquals(1, merged.get(rl("test:c1")).segments().get(0).refs().size());
        assertFalse(merged.containsKey(rl("test:ghost")));
    }

    @Test
    void missingSegmentName_opSkipped_otherOpsStillApply() {
        var raw = chains(chain("test:c1", seg("noun", "test:base")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(
                new NameChainOp.AddRefs(
                    new NameChainOp.SegmentRef.ByName("ghost"),
                    List.of(new NameSegment.WeightedRef(rl("pack:skipped"), 1f))
                ),
                new NameChainOp.AddRefs(
                    new NameChainOp.SegmentRef.ByName("noun"),
                    List.of(new NameSegment.WeightedRef(rl("pack:added"), 1f))
                )
            )
        );

        var merged = ChainMerger.merge(raw, exts(ext));
        var refs = merged.get(rl("test:c1")).segments().get(0).refs();

        assertEquals(2, refs.size());
        assertEquals("pack:added", refs.get(1).ref().toString());
    }

    @Test
    void addSegment_endAppendsAtTail() {
        var raw = chains(chain("test:c1", seg("a", "test:a")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(new NameChainOp.AddSegment(
                new NameChainOp.InsertPos.End(),
                seg("b", "test:b")
            ))
        );

        var merged = ChainMerger.merge(raw, exts(ext));
        var segs = merged.get(rl("test:c1")).segments();

        assertEquals(2, segs.size());
        assertEquals("a", segs.get(0).name());
        assertEquals("b", segs.get(1).name());
    }

    @Test
    void addSegment_startPrependsAtHead() {
        var raw = chains(chain("test:c1", seg("a", "test:a"), seg("b", "test:b")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(new NameChainOp.AddSegment(
                new NameChainOp.InsertPos.Start(),
                seg("z", "test:z")
            ))
        );

        var merged = ChainMerger.merge(raw, exts(ext));
        var segs = merged.get(rl("test:c1")).segments();

        assertEquals(3, segs.size());
        assertEquals("z", segs.get(0).name());
        assertEquals("a", segs.get(1).name());
        assertEquals("b", segs.get(2).name());
    }

    @Test
    void addSegment_atIndexInsertsAtPosition() {
        var raw = chains(chain("test:c1", seg("a", "test:a"), seg("c", "test:c")));
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(new NameChainOp.AddSegment(
                new NameChainOp.InsertPos.At(1),
                seg("b", "test:b")
            ))
        );

        var merged = ChainMerger.merge(raw, exts(ext));
        var segs = merged.get(rl("test:c1")).segments();

        assertEquals(3, segs.size());
        assertEquals("a", segs.get(0).name());
        assertEquals("b", segs.get(1).name());
        assertEquals("c", segs.get(2).name());
    }

    @Test
    void rawChainsAreNotMutated() {
        var baseSeg = seg("noun", "test:base");
        var c1 = chain("test:c1", baseSeg);
        var raw = chains(c1);
        var ext = new NameChainExtension(
            rl("pack:x"),
            rl("test:c1"),
            List.of(new NameChainOp.AddRefs(
                new NameChainOp.SegmentRef.ByName("noun"),
                List.of(new NameSegment.WeightedRef(rl("pack:added"), 1f))
            ))
        );

        ChainMerger.merge(raw, exts(ext));

        assertEquals(1, raw.get(rl("test:c1")).segments().get(0).refs().size(),
            "input chain map should not be mutated by merge");
    }

    @Test
    void emptyExtensions_returnsChainsUnchanged() {
        var raw = chains(chain("test:c1", seg("noun", "test:a", "test:b")));
        var merged = ChainMerger.merge(raw, exts());

        assertEquals(1, merged.size());
        assertEquals(2, merged.get(rl("test:c1")).segments().get(0).refs().size());
    }
}
