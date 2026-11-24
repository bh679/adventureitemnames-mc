package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.compat.Ids;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code skip_if} segment rule: chain JSON, user-config JSON, and override records. */
class NameCodecSkipIfTest {
    private static final ResourceLocation ID = Ids.of("adventureitemnames", "skip_if_test");
    private static final String JSON = """
        {"id":"adventureitemnames:skip_if_test","replace":true,"segments":[
          {"chance":0.04,"label":"Title","refs":[{"ref":"adventureitemnames:titles","weight":1.0}]},
          {"chance":1.0,"label":"Name","refs":[{"ref":"adventureitemnames:names","weight":1.0}]},
          {"chance":0.2,"label":"Fan","skip_if":[0, 2, "x", -1, 2.5, 2],"refs":[{"ref":"adventureitemnames:fan_suffix","weight":1.0}]}
        ]}""";

    @Test
    void parsesValidIndicesAndDropsTheRest() throws Exception {
        NameChain chain = NameCodec.parseChain(JsonParser.parseString(JSON), ID);
        assertEquals(List.of(), chain.segments().get(0).skipIf());
        assertEquals(List.of(0, 2), chain.segments().get(2).skipIf());
    }

    @Test
    void roundTripKeepsRuleAndOmitsItWhenEmpty() throws Exception {
        NameChain chain = NameCodec.parseChain(JsonParser.parseString(JSON), ID);
        JsonObject written = NameCodec.writeChain(chain, true);
        var segs = written.getAsJsonArray("segments");
        assertFalse(segs.get(0).getAsJsonObject().has("skip_if"));
        assertEquals(2, segs.get(2).getAsJsonObject().getAsJsonArray("skip_if").size());
        assertEquals(chain, NameCodec.parseChain(written, ID));
    }

    @Test
    void legacyConstructorsDefaultToNoRule() {
        NameSegment seg = new NameSegment(List.of(), 1f, " ", false, "x", true);
        assertTrue(seg.skipIf().isEmpty());
        assertEquals(List.of(3), seg.withSkipIf(List.of(3)).skipIf());
        assertTrue(seg.withSkipIf(List.of(3)).link());
    }

    @Test
    void userConfigSegmentOverrideCarriesRule() {
        var cfg = ConfigCodec.parse(JsonParser.parseString("""
            {"segment_overrides":{
              "adventureitemnames:skip_if_test#2":{"skip_if":[0]},
              "adventureitemnames:skip_if_test#1":{"skip_if":[]},
              "adventureitemnames:skip_if_test#0":{"skip_if":"nope"}
            }}"""), "test");
        var edits = cfg.segmentOverrides().edits;
        assertEquals(List.of(0), edits.get("adventureitemnames:skip_if_test#2").skipIf());
        // An empty list is a real override: it clears a shipped rule.
        assertEquals(List.of(), edits.get("adventureitemnames:skip_if_test#1").skipIf());
        assertNull(edits.get("adventureitemnames:skip_if_test#0"));
    }

    @Test
    void segmentEditNoOpAndLegacyConstructor() {
        var legacy = new SegmentOverrides.SegmentEdit(null, null, null, null, null, null);
        assertTrue(legacy.isNoOp());
        assertNull(legacy.skipIf());
        assertFalse(legacy.withSkipIf(List.of()).isNoOp());
        var withRefs = SegmentOverrides.SegmentEdit.empty().withSkipIf(List.of(1)).withRefs(List.of());
        assertEquals(List.of(1), withRefs.skipIf());
    }
}
