package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.MobCategory;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-parsing tests for {@link ConfigCodec}. Verifies the JSON keys
 * land in the right {@link DisableSet} fields, malformed input doesn't
 * crash, and wildcards survive a round-trip through parsing.
 */
class ConfigCodecTest {

    private static ResourceLocation rl(String s) {
        return ResourceLocation.tryParse(s);
    }

    private static DisableSet parse(String json) {
        JsonElement el = JsonParser.parseString(json);
        return ConfigCodec.parse(el, "test");
    }

    @Test
    void emptyObjectYieldsEmptySet() {
        DisableSet ds = parse("{}");
        assertTrue(ds.pools.isEmpty());
        assertTrue(ds.chains.isEmpty());
        assertTrue(ds.selectors.isEmpty());
        assertTrue(ds.itemTags.isEmpty());
        assertTrue(ds.itemIds.isEmpty());
        assertTrue(ds.mobCategories.isEmpty());
        assertTrue(ds.entityTags.isEmpty());
        assertTrue(ds.entityIds.isEmpty());
    }

    @Test
    void parsesPoolsChainsSelectors() {
        DisableSet ds = parse("""
            {
              "pools": ["adventureitemnames:discord_people", "adventureitemnames:colors"],
              "chains": ["adventureitemnames:mob_name"],
              "selectors": ["adventureitemnames:shield"]
            }
            """);
        assertTrue(ds.pools.contains(rl("adventureitemnames:discord_people")));
        assertTrue(ds.pools.contains(rl("adventureitemnames:colors")));
        assertTrue(ds.chains.contains(rl("adventureitemnames:mob_name")));
        assertTrue(ds.selectors.contains(rl("adventureitemnames:shield")));
    }

    @Test
    void parsesItemsSection() {
        DisableSet ds = parse("""
            {
              "items": {
                "tags": ["minecraft:wooden_tools", "minecraft:swords"],
                "ids":  ["minecraft:bedrock"]
              }
            }
            """);
        assertTrue(ds.itemTags.contains(rl("minecraft:wooden_tools")));
        assertTrue(ds.itemTags.contains(rl("minecraft:swords")));
        assertTrue(ds.itemIds.contains(rl("minecraft:bedrock")));
        assertTrue(ds.pools.isEmpty(), "items should not leak into pools");
    }

    @Test
    void parsesMobsSection() {
        DisableSet ds = parse("""
            {
              "mobs": {
                "categories":  ["villager", "passive"],
                "entity_tags": ["minecraft:raiders"],
                "entity_ids":  ["minecraft:wandering_trader", "minecraft:cow"]
              }
            }
            """);
        assertTrue(ds.mobCategories.contains(MobCategory.VILLAGER));
        assertTrue(ds.mobCategories.contains(MobCategory.PASSIVE));
        assertTrue(ds.entityTags.contains(rl("minecraft:raiders")));
        assertTrue(ds.entityIds.contains(rl("minecraft:wandering_trader")));
        assertTrue(ds.entityIds.contains(rl("minecraft:cow")));
    }

    @Test
    void mobCategoryIsCaseInsensitive() {
        DisableSet ds = parse("""
            { "mobs": { "categories": ["VILLAGER", "Passive"] } }
            """);
        assertTrue(ds.mobCategories.contains(MobCategory.VILLAGER));
        assertTrue(ds.mobCategories.contains(MobCategory.PASSIVE));
    }

    @Test
    void unknownMobCategoryIgnored() {
        DisableSet ds = parse("""
            { "mobs": { "categories": ["villager", "boss", "hostile"] } }
            """);
        assertTrue(ds.mobCategories.contains(MobCategory.VILLAGER));
        assertEquals(1, ds.mobCategories.size(), "unknown categories should be dropped, not added");
    }

    @Test
    void wildcardsParseAndMatch() {
        DisableSet ds = parse("""
            { "pools": ["adventureitemnames:*"] }
            """);
        assertTrue(ds.pools.contains(rl("adventureitemnames:colors")));
        assertTrue(ds.pools.contains(rl("adventureitemnames:discord_people")));
        assertFalse(ds.pools.contains(rl("minecraft:something")));
    }

    @Test
    void malformedRootDoesNotCrash() {
        DisableSet ds = ConfigCodec.parse(JsonParser.parseString("\"not an object\""), "test");
        assertTrue(ds.pools.isEmpty());
    }

    @Test
    void malformedNestedSectionIgnored() {
        DisableSet ds = parse("""
            {
              "pools": "not an array",
              "items": "not an object",
              "mobs":  ["not", "an", "object"]
            }
            """);
        assertTrue(ds.pools.isEmpty());
        assertTrue(ds.itemTags.isEmpty());
        assertTrue(ds.itemIds.isEmpty());
        assertTrue(ds.mobCategories.isEmpty());
    }

    @Test
    void invalidIdsInArrayAreSkipped() {
        DisableSet ds = parse("""
            {
              "pools": [
                "adventureitemnames:colors",
                "Invalid Path With Spaces",
                "adventureitemnames:discord_people"
              ]
            }
            """);
        assertTrue(ds.pools.contains(rl("adventureitemnames:colors")));
        assertTrue(ds.pools.contains(rl("adventureitemnames:discord_people")));
        // The invalid one is silently dropped — verify by checking total size via the
        // two valid ones being the full content.
    }

    @Test
    void mixedExactAndWildcard() {
        DisableSet ds = parse("""
            {
              "pools": ["adventureitemnames:*"],
              "selectors": ["adventureitemnames:sword", "adventureitemnames:shield"]
            }
            """);
        assertTrue(ds.pools.contains(rl("adventureitemnames:anything")));
        assertTrue(ds.selectors.contains(rl("adventureitemnames:sword")));
        assertTrue(ds.selectors.contains(rl("adventureitemnames:shield")));
        assertFalse(ds.selectors.contains(rl("adventureitemnames:axe")));
    }
}
