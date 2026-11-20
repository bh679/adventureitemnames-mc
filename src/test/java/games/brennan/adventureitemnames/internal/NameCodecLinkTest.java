package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.compat.Ids;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code link} segment flag survives parse → write → parse and defaults to false. */
class NameCodecLinkTest {
    private static final ResourceLocation ID = Ids.of("adventureitemnames", "link_test");
    private static final String JSON = """
        {"id":"adventureitemnames:link_test","replace":true,"segments":[
          {"chance":1.0,"label":"Ending","refs":[{"ref":"adventureitemnames:name_ending","weight":1.0}]},
          {"chance":0.5,"label":"Link vowel","link":true,"refs":[{"ref":"adventureitemnames:name_vowel","weight":1.0}]}
        ]}""";

    @Test
    void parsesLinkAndDefaultsFalse() throws Exception {
        NameChain chain = NameCodec.parseChain(JsonParser.parseString(JSON), ID);
        assertFalse(chain.segments().get(0).link());
        assertTrue(chain.segments().get(1).link());
    }

    @Test
    void roundTripKeepsLinkAndOmitsItWhenFalse() throws Exception {
        NameChain chain = NameCodec.parseChain(JsonParser.parseString(JSON), ID);
        JsonObject written = NameCodec.writeChain(chain, true);
        var segs = written.getAsJsonArray("segments");
        assertFalse(segs.get(0).getAsJsonObject().has("link"));
        assertTrue(segs.get(1).getAsJsonObject().get("link").getAsBoolean());
        NameChain again = NameCodec.parseChain(written, ID);
        assertEquals(chain, again);
    }
}
