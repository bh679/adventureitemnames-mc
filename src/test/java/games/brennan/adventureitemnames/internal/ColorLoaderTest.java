package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.compat.Ids;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-parsing tests for {@link ColorLoader}. Verifies valid color
 * strings round-trip through the loader into the datapack-color layer,
 * invalid / unknown / non-color values are rejected, and the
 * underscore-prefixed comment-key convention is silent.
 *
 * <p>The loader writes its merged output to the singleton
 * {@link NamingConfig#setDatapackColors} layer, so each test asserts via
 * {@link NamingConfig#colorFor} and resets the layer in {@link AfterEach}
 * to prevent cross-test contamination.
 */
class ColorLoaderTest {

    private static final ResourceLocation FILE_ID =
        Ids.of("adventureitemnames", "defaults");

    @AfterEach
    void resetDatapackColors() {
        NamingConfig.setDatapackColors(new ColorOverrides());
    }

    /** Drive {@link ColorLoader#apply} with one synthetic file's worth of JSON. */
    private static void apply(String json) {
        Map<ResourceLocation, JsonElement> objects = new LinkedHashMap<>();
        objects.put(FILE_ID, JsonParser.parseString(json));
        new ColorLoader().apply(objects, null, null);
    }

    @Test
    void emptyObjectLeavesAllKeysUnset() {
        apply("{}");
        for (ChanceKind k : ChanceKind.values()) {
            assertTrue(NamingConfig.colorFor(k).isEmpty(), k + " should have no color");
        }
    }

    @Test
    void parsesValidColorByLowercaseName() {
        apply("""
            { "description_plain": "dark_gray" }
            """);
        assertEquals(ChatFormatting.DARK_GRAY,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).orElseThrow());
    }

    @Test
    void parsesValidColorCaseInsensitively() {
        apply("""
            { "description_enchanted": "GOLD" }
            """);
        assertEquals(ChatFormatting.GOLD,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_ENCHANTED).orElseThrow());
    }

    @Test
    void underscorePrefixedKeysSilentlySkipped() {
        apply("""
            {
              "_comment": "this is a comment, not a color",
              "_version": "1.0",
              "description_plain": "blue"
            }
            """);
        assertEquals(ChatFormatting.BLUE,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).orElseThrow());
    }

    @Test
    void unknownChanceKindKeyRejected() {
        apply("""
            {
              "not_a_real_kind": "red",
              "description_plain": "green"
            }
            """);
        assertEquals(ChatFormatting.GREEN,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).orElseThrow());
        // The unknown key didn't populate any other kind:
        for (ChanceKind k : ChanceKind.values()) {
            if (k == ChanceKind.DESCRIPTION_PLAIN) continue;
            assertTrue(NamingConfig.colorFor(k).isEmpty());
        }
    }

    @Test
    void nonStringValueRejected() {
        apply("""
            {
              "description_plain": 42,
              "description_enchanted": "aqua"
            }
            """);
        assertTrue(NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).isEmpty());
        assertEquals(ChatFormatting.AQUA,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_ENCHANTED).orElseThrow());
    }

    @Test
    void unknownColorNameRejected() {
        apply("""
            {
              "description_plain": "not_a_color",
              "description_enchanted": "yellow"
            }
            """);
        assertTrue(NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).isEmpty());
        assertEquals(ChatFormatting.YELLOW,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_ENCHANTED).orElseThrow());
    }

    @Test
    void nonColorFormattingRejected() {
        // bold / italic / reset are valid ChatFormatting values but
        // ChatFormatting.isColor() returns false for them.
        apply("""
            {
              "description_plain": "bold",
              "description_enchanted": "italic",
              "mob_villager":        "red"
            }
            """);
        assertTrue(NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).isEmpty());
        assertTrue(NamingConfig.colorFor(ChanceKind.DESCRIPTION_ENCHANTED).isEmpty());
        assertEquals(ChatFormatting.RED,
            NamingConfig.colorFor(ChanceKind.MOB_VILLAGER).orElseThrow());
    }

    @Test
    void multipleFilesUnion_lastWinsOnCollision() {
        // ResourceLocation natural ordering is by string. Putting two
        // entries in the map with the same key the loader uses
        // (alphabetical map iteration), the later one clobbers the
        // earlier — mirrors ChanceLoader semantics.
        Map<ResourceLocation, JsonElement> objects = new LinkedHashMap<>();
        objects.put(Ids.of("adventureitemnames", "a_first"),
            JsonParser.parseString("{ \"description_plain\": \"red\", \"mob_villager\": \"gold\" }"));
        objects.put(Ids.of("adventureitemnames", "b_second"),
            JsonParser.parseString("{ \"description_plain\": \"blue\" }"));
        new ColorLoader().apply(objects, null, null);

        // Later file clobbered the description_plain key, but mob_villager
        // from the first file survives (not overridden).
        assertEquals(ChatFormatting.BLUE,
            NamingConfig.colorFor(ChanceKind.DESCRIPTION_PLAIN).orElseThrow());
        assertEquals(ChatFormatting.GOLD,
            NamingConfig.colorFor(ChanceKind.MOB_VILLAGER).orElseThrow());
    }

    @Test
    void nonObjectRootSkipped() {
        // A file whose root is a JSON array (not an object) should be
        // logged and skipped — must not crash.
        Map<ResourceLocation, JsonElement> objects = new LinkedHashMap<>();
        objects.put(FILE_ID, JsonParser.parseString("[\"not\", \"an\", \"object\"]"));
        assertDoesNotThrow(() -> new ColorLoader().apply(objects, null, null));
        for (ChanceKind k : ChanceKind.values()) {
            assertTrue(NamingConfig.colorFor(k).isEmpty());
        }
    }
}
