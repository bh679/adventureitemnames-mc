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
    void chanceRulesParseRoundTripAndDropBadRules() throws Exception {
        String json = """
            {"id":"adventureitemnames:skip_if_test","replace":true,"segments":[
              {"chance":1.0,"label":"Name","refs":[{"ref":"adventureitemnames:names","weight":1.0}]},
              {"chance":0.2,"label":"Fan","refs":[{"ref":"adventureitemnames:fan_suffix","weight":1.0}],
               "chance_if":[
                 {"when":[{"segment":0,"refs":["adventureitemnames:discord_people","dungeontrain:backers"]}],"chance":0.5},
                 {"when":[{"segment":0}],"chance":7},
                 {"when":[{"segment":-1}],"chance":0.3},
                 {"when":[],"chance":0.3},
                 {"when":[{"segment":0}]},
                 {"when":[{"segment":0,"refs":"nope"}],"chance":0.3}
               ]}
            ]}""";
        NameChain chain = NameCodec.parseChain(JsonParser.parseString(json), ID);
        var rules = chain.segments().get(1).chanceIf();
        assertEquals(2, rules.size());
        assertEquals(0.5f, rules.get(0).chance());
        assertEquals(2, rules.get(0).when().get(0).refs().size());
        assertEquals(1f, rules.get(1).chance(), "chance is clamped to 1");
        assertTrue(chain.segments().get(0).chanceIf().isEmpty());
        JsonObject written = NameCodec.writeChain(chain, true);
        assertFalse(written.getAsJsonArray("segments").get(0).getAsJsonObject().has("chance_if"));
        assertEquals(chain, NameCodec.parseChain(written, ID));
    }

    @Test
    void chanceRulesRemapAndDropRulesOnRemovedSegments() {
        var keep = new NameSegment.ChanceRule(List.of(new NameSegment.Condition(2, List.of())), 0.5f);
        var gone = new NameSegment.ChanceRule(List.of(new NameSegment.Condition(1, List.of())), 0.5f);
        var out = ChanceRuleCodec.remap(List.of(keep, gone), java.util.Map.of(0, 0, 2, 1));
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).when().get(0).segment());
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
