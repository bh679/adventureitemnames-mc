package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.MobCategory;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Parses an enable/disable config JSON into a {@link DisableSet}.
 * Missing fields default to empty; unknown keys are logged at WARN and
 * ignored. Wildcards of the form {@code namespace:*} are accepted in any
 * id list and resolved at query time.
 *
 * <p>Schema (all fields optional):
 * <pre>{@code
 * {
 *   "pools":     ["adventureitemnames:discord_people"],
 *   "chains":    [],
 *   "selectors": ["adventureitemnames:shield"],
 *   "items": {
 *     "tags": ["minecraft:wooden_tools"],
 *     "ids":  ["minecraft:bedrock"]
 *   },
 *   "mobs": {
 *     "categories":  ["passive"],
 *     "entity_tags": ["minecraft:raiders"],
 *     "entity_ids":  ["minecraft:wandering_trader"]
 *   }
 * }
 * }</pre>
 */
public final class ConfigCodec {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConfigCodec() {}

    public static DisableSet parse(JsonElement root, String sourceLabel) {
        DisableSet out = new DisableSet();
        if (root == null || !root.isJsonObject()) {
            if (root != null) {
                LOGGER.warn("[AdventureItemNames] config '{}' root is not a JSON object — ignoring", sourceLabel);
            }
            return out;
        }
        JsonObject obj = root.getAsJsonObject();

        readIdList(obj, "pools", out.pools, sourceLabel);
        readIdList(obj, "chains", out.chains, sourceLabel);
        readIdList(obj, "selectors", out.selectors, sourceLabel);

        JsonElement itemsEl = obj.get("items");
        if (itemsEl != null && itemsEl.isJsonObject()) {
            JsonObject itemsObj = itemsEl.getAsJsonObject();
            readIdList(itemsObj, "tags", out.itemTags, sourceLabel + "/items");
            readIdList(itemsObj, "ids", out.itemIds, sourceLabel + "/items");
        } else if (itemsEl != null) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'items' is not an object — ignoring", sourceLabel);
        }

        JsonElement mobsEl = obj.get("mobs");
        if (mobsEl != null && mobsEl.isJsonObject()) {
            JsonObject mobsObj = mobsEl.getAsJsonObject();
            JsonElement catsEl = mobsObj.get("categories");
            if (catsEl != null) {
                if (catsEl.isJsonArray()) {
                    for (JsonElement e : catsEl.getAsJsonArray()) {
                        if (!e.isJsonPrimitive()) continue;
                        MobCategory cat = MobCategory.fromKey(e.getAsString());
                        if (cat == null) {
                            LOGGER.warn("[AdventureItemNames] config '{}' unknown mob category '{}'", sourceLabel, e.getAsString());
                            continue;
                        }
                        out.mobCategories.add(cat);
                    }
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' 'mobs/categories' is not an array", sourceLabel);
                }
            }
            readIdList(mobsObj, "entity_tags", out.entityTags, sourceLabel + "/mobs");
            readIdList(mobsObj, "entity_ids", out.entityIds, sourceLabel + "/mobs");
        } else if (mobsEl != null) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'mobs' is not an object — ignoring", sourceLabel);
        }
        return out;
    }

    public static DisableSet parse(InputStream in, String sourceLabel) {
        try {
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(root, sourceLabel);
        } catch (Exception e) {
            LOGGER.warn("[AdventureItemNames] config '{}' failed to parse: {}", sourceLabel, e.getMessage());
            return new DisableSet();
        }
    }

    private static void readIdList(JsonObject obj, String key, DisableSet.IdSet dest, String sourceLabel) {
        JsonElement el = obj.get(key);
        if (el == null) return;
        if (!el.isJsonArray()) {
            LOGGER.warn("[AdventureItemNames] config '{}' key '{}' is not an array — ignoring", sourceLabel, key);
            return;
        }
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) continue;
            String s = item.getAsString();
            if (!dest.addRaw(s)) {
                LOGGER.warn("[AdventureItemNames] config '{}' invalid id '{}' under '{}'", sourceLabel, s, key);
            }
        }
    }
}
