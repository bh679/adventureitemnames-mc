package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-parsing tests for the {@code pool_entry_overrides} field of
 * {@link ConfigCodec}. Verifies adds/removes land in {@link EntryOverrides},
 * malformed input is skipped with WARN rather than crashing, and the
 * field is fully backwards-compatible (absent → empty overrides).
 */
class ConfigCodecPoolEntryTest {

    private static ResourceLocation rl(String s) {
        return ResourceLocation.tryParse(s);
    }

    private static EntryOverrides parse(String json) {
        JsonElement el = JsonParser.parseString(json);
        return ConfigCodec.parse(el, "test").entries();
    }

    @Test
    void absentFieldYieldsEmptyOverrides() {
        EntryOverrides eo = parse("{}");
        assertTrue(eo.isEmpty());
    }

    @Test
    void parsesAddedAndRemovedForOnePool() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "added":   [{ "text": "Magenta", "item_types": [] }],
                  "removed": ["Red", "Blue"]
                }
              }
            }
            """);
        ResourceLocation pool = rl("adventureitemnames:colors");
        assertEquals(1, eo.added.get(pool).size());
        assertEquals("Magenta", eo.added.get(pool).get(0).text());
        assertTrue(eo.added.get(pool).get(0).itemTypes().isEmpty());
        assertTrue(eo.removed.get(pool).contains("Red"));
        assertTrue(eo.removed.get(pool).contains("Blue"));
    }

    @Test
    void parsesItemTypesOnAdded() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "added": [{ "text": "Blade", "item_types": ["minecraft:swords"] }]
                }
              }
            }
            """);
        var entry = eo.added.get(rl("adventureitemnames:colors")).get(0);
        assertEquals("Blade", entry.text());
        assertEquals(1, entry.itemTypes().size());
        assertEquals(rl("minecraft:swords"), entry.itemTypes().get(0));
    }

    @Test
    void emptyTextEntryIsRejected() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "added": [
                    { "text": "" },
                    { "text": "   " },
                    { "text": "Magenta" }
                  ]
                }
              }
            }
            """);
        var list = eo.added.get(rl("adventureitemnames:colors"));
        assertEquals(1, list.size());
        assertEquals("Magenta", list.get(0).text());
    }

    @Test
    void overlongTextIsClamped() {
        String huge = "x".repeat(500);
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "added": [{ "text": "%s" }]
                }
              }
            }
            """.formatted(huge));
        var entry = eo.added.get(rl("adventureitemnames:colors")).get(0);
        assertEquals(256, entry.text().length());
    }

    @Test
    void malformedItemTypesSkippedNotRejected() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "added": [
                    { "text": "Blade", "item_types": ["Not A Valid RL", "minecraft:swords", 42] }
                  ]
                }
              }
            }
            """);
        var entry = eo.added.get(rl("adventureitemnames:colors")).get(0);
        assertEquals("Blade", entry.text());
        assertEquals(1, entry.itemTypes().size());
        assertEquals(rl("minecraft:swords"), entry.itemTypes().get(0));
    }

    @Test
    void invalidPoolIdKeyIsSkipped() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "Not A Valid RL": { "added": [{ "text": "Whatever" }] },
                "adventureitemnames:colors": { "added": [{ "text": "Magenta" }] }
              }
            }
            """);
        assertEquals(1, eo.added.size());
        assertNotNull(eo.added.get(rl("adventureitemnames:colors")));
    }

    @Test
    void wrongTypesAreIgnoredNotCrashed() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": "not an object"
            }
            """);
        assertTrue(eo.isEmpty());

        EntryOverrides eo2 = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "added":   "not an array",
                  "removed": { "still": "wrong" }
                }
              }
            }
            """);
        assertTrue(eo2.isEmpty());
    }

    @Test
    void removedContainingNonStringIsSkipped() {
        EntryOverrides eo = parse("""
            {
              "pool_entry_overrides": {
                "adventureitemnames:colors": {
                  "removed": ["Red", 42, "Blue"]
                }
              }
            }
            """);
        var set = eo.removed.get(rl("adventureitemnames:colors"));
        assertEquals(2, set.size());
        assertTrue(set.contains("Red"));
        assertTrue(set.contains("Blue"));
    }
}
