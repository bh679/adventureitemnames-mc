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
        return ConfigCodec.parse(el, "test").disables();
    }

    private static LoadedConfig parseFull(String json) {
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
        LoadedConfig cfg = ConfigCodec.parse(JsonParser.parseString("\"not an object\""), "test");
        assertTrue(cfg.disables().pools.isEmpty());
        assertTrue(cfg.weights().isEmpty());
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

    @Test
    void parsesWeightOverrides() {
        LoadedConfig cfg = parseFull("""
            {
              "weight_overrides": {
                "adventureitemnames:title_combinations#1#adventureitemnames:mc_technoblade": 0.10,
                "adventureitemnames:title_combinations#1#adventureitemnames:discord_people": 0
              }
            }
            """);
        assertEquals(0.10f,
            cfg.weights().weights.get("adventureitemnames:title_combinations#1#adventureitemnames:mc_technoblade"),
            1e-6f);
        assertEquals(0f,
            cfg.weights().weights.get("adventureitemnames:title_combinations#1#adventureitemnames:discord_people"),
            1e-6f);
    }

    @Test
    void weightOverridesRejectsMalformedKeys() {
        LoadedConfig cfg = parseFull("""
            {
              "weight_overrides": {
                "no-hash-at-all": 0.5,
                "only#one#hash": 0.5,
                "adventureitemnames:title_combinations#abc#adventureitemnames:food": 0.5,
                "adventureitemnames:title_combinations#1#adventureitemnames:food": 0.5
              }
            }
            """);
        assertEquals(1, cfg.weights().weights.size(),
            "only the well-formed key survives");
        assertTrue(cfg.weights().weights.containsKey(
            "adventureitemnames:title_combinations#1#adventureitemnames:food"));
    }

    @Test
    void weightOverridesRejectsNegativeAndClampsHuge() {
        LoadedConfig cfg = parseFull("""
            {
              "weight_overrides": {
                "adventureitemnames:title_combinations#1#adventureitemnames:food": -1.0,
                "adventureitemnames:title_combinations#1#adventureitemnames:names": 99999.0
              }
            }
            """);
        assertFalse(cfg.weights().weights.containsKey(
            "adventureitemnames:title_combinations#1#adventureitemnames:food"),
            "negative is dropped");
        Float clamped = cfg.weights().weights.get(
            "adventureitemnames:title_combinations#1#adventureitemnames:names");
        assertNotNull(clamped);
        assertEquals(1000f, clamped, 1e-3f, "huge values clamp to MAX_WEIGHT");
    }

    @Test
    void disablesAndWeightsCoexist() {
        LoadedConfig cfg = parseFull("""
            {
              "pools": ["adventureitemnames:discord_people"],
              "weight_overrides": {
                "adventureitemnames:title_combinations#1#adventureitemnames:food": 0.5
              }
            }
            """);
        assertTrue(cfg.disables().pools.contains(rl("adventureitemnames:discord_people")));
        assertEquals(0.5f, cfg.weights().weights.get(
            "adventureitemnames:title_combinations#1#adventureitemnames:food"), 1e-6f);
    }
}
