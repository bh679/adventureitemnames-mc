package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-parsing tests for the {@code description_tiers} field on
 * selector JSON. Covers backward-compat (absent key), full and partial
 * tier maps, malformed entries, and a writer→parser round-trip via
 * {@link PackSelectorWriter#buildSelectorJson}.
 */
class NameCodecSelectorTest {

    private static final ResourceLocation FALLBACK =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "test");

    private static NameSelector parse(String json) throws NameCodec.NameParseException {
        JsonElement el = JsonParser.parseString(json);
        return NameCodec.parseSelector(el, FALLBACK);
    }

    @Test
    void parsesSelectorWithoutDescriptionTiersReturnsEmptyMap() throws Exception {
        String json = """
            {
              "id": "adventureitemnames:sword",
              "applies_to": "minecraft:swords",
              "tiers": {
                "plain": "adventureitemnames:weapon_name_short",
                "enchanted": "adventureitemnames:weapon_name_full"
              }
            }
            """;
        NameSelector sel = parse(json);
        assertNotNull(sel);
        assertEquals(2, sel.tiers().size());
        assertTrue(sel.descriptionTiers().isEmpty(),
            "absent description_tiers must default to empty map");
    }

    @Test
    void parsesSelectorWithDescriptionTiers() throws Exception {
        String json = """
            {
              "id": "adventureitemnames:sword",
              "applies_to": "minecraft:swords",
              "tiers": {
                "plain": "adventureitemnames:weapon_name_short"
              },
              "description_tiers": {
                "plain": "adventureitemnames:weapon_desc_short",
                "enchanted": "adventureitemnames:weapon_desc_full"
              }
            }
            """;
        NameSelector sel = parse(json);
        assertEquals(2, sel.descriptionTiers().size());
        assertEquals(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "weapon_desc_short"),
            sel.descriptionTiers().get("plain"));
        assertEquals(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "weapon_desc_full"),
            sel.descriptionTiers().get("enchanted"));
    }

    @Test
    void partialDescriptionTiersParseCorrectly() throws Exception {
        String onlyEnchanted = """
            {
              "id": "adventureitemnames:sword",
              "applies_to": "minecraft:swords",
              "tiers": { "plain": "adventureitemnames:weapon_name_short" },
              "description_tiers": { "enchanted": "adventureitemnames:weapon_desc_full" }
            }
            """;
        NameSelector sel = parse(onlyEnchanted);
        assertEquals(1, sel.descriptionTiers().size());
        assertTrue(sel.descriptionTiers().containsKey("enchanted"));
        assertFalse(sel.descriptionTiers().containsKey("plain"));
    }

    @Test
    void descriptionTiersWithInvalidEntriesAreSkipped() throws Exception {
        // Non-primitive value (array) and unparseable RL — both must be dropped, not throw.
        String json = """
            {
              "id": "adventureitemnames:sword",
              "applies_to": "minecraft:swords",
              "tiers": { "plain": "adventureitemnames:weapon_name_short" },
              "description_tiers": {
                "plain": "adventureitemnames:weapon_desc_short",
                "broken": ["nope"],
                "garbage": "NOT A VALID RL!!!"
              }
            }
            """;
        NameSelector sel = parse(json);
        assertEquals(1, sel.descriptionTiers().size(),
            "only the well-formed entry should survive");
        assertEquals(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "weapon_desc_short"),
            sel.descriptionTiers().get("plain"));
    }

    @Test
    void writerRoundTripPreservesDescriptionTiers() throws Exception {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("adventureitemnames", "sword");
        ResourceLocation appliesTo = ResourceLocation.fromNamespaceAndPath("minecraft", "swords");
        ResourceLocation nameChain = ResourceLocation.fromNamespaceAndPath("adventureitemnames", "weapon_name_full");
        ResourceLocation descChain = ResourceLocation.fromNamespaceAndPath("adventureitemnames", "weapon_desc_full");

        NameSelector original = new NameSelector(id, appliesTo,
            java.util.Map.of("plain", nameChain, "enchanted", nameChain),
            java.util.Map.of("enchanted", descChain));

        JsonObject built = PackSelectorWriter.buildSelectorJson(original);
        assertTrue(built.has("description_tiers"),
            "writer must emit description_tiers when the map is non-empty");

        NameSelector roundTripped = NameCodec.parseSelector(built, FALLBACK);
        assertEquals(original.id(), roundTripped.id());
        assertEquals(original.appliesTo(), roundTripped.appliesTo());
        assertEquals(original.tiers(), roundTripped.tiers());
        assertEquals(original.descriptionTiers(), roundTripped.descriptionTiers());
    }

    @Test
    void writerOmitsEmptyDescriptionTiers() {
        // Shipped 11 selector JSONs all have empty descriptionTiers — the writer
        // must not emit the key, so re-saving them stays diff-clean.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("adventureitemnames", "sword");
        ResourceLocation appliesTo = ResourceLocation.fromNamespaceAndPath("minecraft", "swords");
        ResourceLocation nameChain = ResourceLocation.fromNamespaceAndPath("adventureitemnames", "weapon_name_full");

        NameSelector noDesc = new NameSelector(id, appliesTo,
            java.util.Map.of("plain", nameChain));

        JsonObject built = PackSelectorWriter.buildSelectorJson(noDesc);
        assertFalse(built.has("description_tiers"),
            "writer must omit description_tiers when the map is empty");
    }
}
