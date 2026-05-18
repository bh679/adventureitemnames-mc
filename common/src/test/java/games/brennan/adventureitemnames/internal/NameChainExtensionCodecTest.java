package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.NameChainExtension;
import games.brennan.adventureitemnames.api.NameChainOp;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-parsing tests for {@link NameCodec#parseChainExtension}. Covers
 * the two v1 ops, named/index segment targeting, the three insertion
 * positions, and the rejection paths for malformed input.
 */
class NameChainExtensionCodecTest {

    private static final ResourceLocation FALLBACK_ID =
        ResourceLocation.fromNamespaceAndPath("test", "ext");

    private static NameChainExtension parse(String json) throws NameCodec.NameParseException {
        JsonElement el = JsonParser.parseString(json);
        return NameCodec.parseChainExtension(el, FALLBACK_ID);
    }

    @Test
    void parsesAddRefsByName() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:atla",
              "target": "adventureitemnames:title_combinations",
              "operations": [
                {
                  "type": "add_refs",
                  "segment": "noun_pool",
                  "refs": [
                    { "ref": "test:atla_characters", "weight": 0.03 }
                  ]
                }
              ]
            }
            """);
        assertEquals("test:atla", ext.id().toString());
        assertEquals("adventureitemnames:title_combinations", ext.target().toString());
        assertEquals(1, ext.operations().size());
        NameChainOp.AddRefs op = (NameChainOp.AddRefs) ext.operations().get(0);
        assertInstanceOf(NameChainOp.SegmentRef.ByName.class, op.target());
        assertEquals("noun_pool", ((NameChainOp.SegmentRef.ByName) op.target()).name());
        assertEquals(1, op.refs().size());
        assertEquals(0.03f, op.refs().get(0).weight(), 0.0001f);
    }

    @Test
    void parsesAddRefsByIndex() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:idx",
              "target": "adventureitemnames:title_combinations",
              "operations": [
                {
                  "type": "add_refs",
                  "segment": 1,
                  "refs": [{ "ref": "test:pool_a", "weight": 1.0 }]
                }
              ]
            }
            """);
        NameChainOp.AddRefs op = (NameChainOp.AddRefs) ext.operations().get(0);
        assertInstanceOf(NameChainOp.SegmentRef.ByIndex.class, op.target());
        assertEquals(1, ((NameChainOp.SegmentRef.ByIndex) op.target()).index());
    }

    @Test
    void parsesAddSegmentEndDefault() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:end",
              "target": "test:chain",
              "operations": [
                {
                  "type": "add_segment",
                  "segment": {
                    "name": "epithet",
                    "refs": [{ "ref": "test:pool_a", "weight": 1.0 }],
                    "chance": 0.5
                  }
                }
              ]
            }
            """);
        NameChainOp.AddSegment op = (NameChainOp.AddSegment) ext.operations().get(0);
        assertInstanceOf(NameChainOp.InsertPos.End.class, op.at());
        assertEquals("epithet", op.segment().name());
        assertEquals(0.5f, op.segment().chance(), 0.0001f);
    }

    @Test
    void parsesAddSegmentStart() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:start",
              "target": "test:chain",
              "operations": [
                {
                  "type": "add_segment",
                  "at": "start",
                  "segment": {
                    "refs": [{ "ref": "test:pool_a", "weight": 1.0 }]
                  }
                }
              ]
            }
            """);
        NameChainOp.AddSegment op = (NameChainOp.AddSegment) ext.operations().get(0);
        assertInstanceOf(NameChainOp.InsertPos.Start.class, op.at());
    }

    @Test
    void parsesAddSegmentAtIndex() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:at",
              "target": "test:chain",
              "operations": [
                {
                  "type": "add_segment",
                  "at": 2,
                  "segment": {
                    "refs": [{ "ref": "test:pool_a", "weight": 1.0 }]
                  }
                }
              ]
            }
            """);
        NameChainOp.AddSegment op = (NameChainOp.AddSegment) ext.operations().get(0);
        assertInstanceOf(NameChainOp.InsertPos.At.class, op.at());
        assertEquals(2, ((NameChainOp.InsertPos.At) op.at()).index());
    }

    @Test
    void rejectsMissingTarget() {
        assertThrows(NameCodec.NameParseException.class, () -> parse("""
            { "id": "test:x", "operations": [{"type":"add_refs","segment":"s","refs":[{"ref":"t:r"}]}] }
            """));
    }

    @Test
    void rejectsEmptyOperations() {
        assertThrows(NameCodec.NameParseException.class, () -> parse("""
            { "id": "test:x", "target": "test:chain", "operations": [] }
            """));
    }

    @Test
    void rejectsMissingOperations() {
        assertThrows(NameCodec.NameParseException.class, () -> parse("""
            { "id": "test:x", "target": "test:chain" }
            """));
    }

    @Test
    void toleratesUnknownOpType() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:mixed",
              "target": "test:chain",
              "operations": [
                { "type": "remove_refs", "segment": "x" },
                { "type": "add_refs", "segment": "s", "refs": [{ "ref": "test:r", "weight": 1.0 }] }
              ]
            }
            """);
        assertEquals(1, ext.operations().size(), "unknown op type should be silently dropped");
        assertInstanceOf(NameChainOp.AddRefs.class, ext.operations().get(0));
    }

    @Test
    void dropsAddRefsWithEmptyRefs() throws Exception {
        NameChainExtension ext = parse("""
            {
              "id": "test:empty",
              "target": "test:chain",
              "operations": [
                { "type": "add_refs", "segment": "s", "refs": [] }
              ]
            }
            """);
        assertTrue(ext.operations().isEmpty(),
            "add_refs with no refs is meaningless and should be dropped");
    }
}
