package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.MobCategory;
import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an enable/disable + weight-override config JSON into a
 * {@link LoadedConfig}. Missing fields default to empty; unknown keys are
 * logged at WARN and ignored. Wildcards of the form {@code namespace:*}
 * are accepted in any id list and resolved at query time.
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
 *   },
 *   "weight_overrides": {
 *     "adventureitemnames:title_combinations#1#adventureitemnames:mc_technoblade": 0.10
 *   }
 * }
 * }</pre>
 */
public final class ConfigCodec {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Defensive upper bound on any single override weight. */
    private static final float MAX_WEIGHT = 1000f;
    /** Defensive upper bound on pool-entry text length — prevents pathologically large names. */
    static final int MAX_ENTRY_TEXT_LEN = 256;

    private ConfigCodec() {}

    /** Parse the entire {@code adventureitemnames.json} schema. */
    public static LoadedConfig parse(JsonElement root, String sourceLabel) {
        DisableSet disables = new DisableSet();
        WeightOverrides weights = new WeightOverrides();
        EntryOverrides entries = new EntryOverrides();
        if (root == null || !root.isJsonObject()) {
            if (root != null) {
                LOGGER.warn("[AdventureItemNames] config '{}' root is not a JSON object — ignoring", sourceLabel);
            }
            return new LoadedConfig(disables, weights, entries);
        }
        JsonObject obj = root.getAsJsonObject();

        readIdList(obj, "pools", disables.pools, sourceLabel);
        readIdList(obj, "chains", disables.chains, sourceLabel);
        readIdList(obj, "selectors", disables.selectors, sourceLabel);

        JsonElement itemsEl = obj.get("items");
        if (itemsEl != null && itemsEl.isJsonObject()) {
            JsonObject itemsObj = itemsEl.getAsJsonObject();
            readIdList(itemsObj, "tags", disables.itemTags, sourceLabel + "/items");
            readIdList(itemsObj, "ids", disables.itemIds, sourceLabel + "/items");
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
                        disables.mobCategories.add(cat);
                    }
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' 'mobs/categories' is not an array", sourceLabel);
                }
            }
            readIdList(mobsObj, "entity_tags", disables.entityTags, sourceLabel + "/mobs");
            readIdList(mobsObj, "entity_ids", disables.entityIds, sourceLabel + "/mobs");
        } else if (mobsEl != null) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'mobs' is not an object — ignoring", sourceLabel);
        }

        JsonElement weightsEl = obj.get("weight_overrides");
        if (weightsEl != null && weightsEl.isJsonObject()) {
            for (var entry : weightsEl.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                JsonElement valEl = entry.getValue();
                if (valEl == null || !valEl.isJsonPrimitive() || !valEl.getAsJsonPrimitive().isNumber()) {
                    LOGGER.warn("[AdventureItemNames] config '{}' weight_overrides['{}'] is not a number — ignoring", sourceLabel, key);
                    continue;
                }
                if (!isValidWeightKey(key)) {
                    LOGGER.warn("[AdventureItemNames] config '{}' weight_overrides key '{}' is malformed — ignoring", sourceLabel, key);
                    continue;
                }
                float v = valEl.getAsFloat();
                if (v < 0f) {
                    LOGGER.warn("[AdventureItemNames] config '{}' weight_overrides['{}'] is negative — ignoring", sourceLabel, key);
                    continue;
                }
                if (v > MAX_WEIGHT) {
                    LOGGER.warn("[AdventureItemNames] config '{}' weight_overrides['{}'] exceeds {} — clamping", sourceLabel, key, MAX_WEIGHT);
                    v = MAX_WEIGHT;
                }
                weights.weights.put(key, v);
            }
        } else if (weightsEl != null) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'weight_overrides' is not an object — ignoring", sourceLabel);
        }

        JsonElement entriesEl = obj.get("pool_entry_overrides");
        if (entriesEl != null && entriesEl.isJsonObject()) {
            for (var poolEntry : entriesEl.getAsJsonObject().entrySet()) {
                ResourceLocation poolId = ResourceLocation.tryParse(poolEntry.getKey());
                if (poolId == null) {
                    LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides key '{}' is not a valid resource location — ignoring",
                        sourceLabel, poolEntry.getKey());
                    continue;
                }
                JsonElement bodyEl = poolEntry.getValue();
                if (bodyEl == null || !bodyEl.isJsonObject()) {
                    LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'] is not an object — ignoring",
                        sourceLabel, poolId);
                    continue;
                }
                JsonObject body = bodyEl.getAsJsonObject();
                readRemovedTexts(body, poolId, entries, sourceLabel);
                readAddedEntries(body, poolId, entries, sourceLabel);
            }
        } else if (entriesEl != null) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'pool_entry_overrides' is not an object — ignoring", sourceLabel);
        }
        return new LoadedConfig(disables, weights, entries);
    }

    private static void readRemovedTexts(JsonObject body, ResourceLocation poolId,
                                         EntryOverrides entries, String sourceLabel) {
        JsonElement removedEl = body.get("removed");
        if (removedEl == null) return;
        if (!removedEl.isJsonArray()) {
            LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].removed is not an array — ignoring",
                sourceLabel, poolId);
            return;
        }
        for (JsonElement item : removedEl.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].removed contains a non-string — skipping",
                    sourceLabel, poolId);
                continue;
            }
            String text = item.getAsString();
            if (text.isEmpty()) continue;
            entries.removeText(poolId, text);
        }
    }

    private static void readAddedEntries(JsonObject body, ResourceLocation poolId,
                                         EntryOverrides entries, String sourceLabel) {
        JsonElement addedEl = body.get("added");
        if (addedEl == null) return;
        if (!addedEl.isJsonArray()) {
            LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added is not an array — ignoring",
                sourceLabel, poolId);
            return;
        }
        for (JsonElement el : addedEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added has a non-object entry — skipping",
                    sourceLabel, poolId);
                continue;
            }
            JsonObject ent = el.getAsJsonObject();
            JsonElement textEl = ent.get("text");
            if (textEl == null || !textEl.isJsonPrimitive() || !textEl.getAsJsonPrimitive().isString()) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added entry missing 'text' — skipping",
                    sourceLabel, poolId);
                continue;
            }
            String text = textEl.getAsString().trim();
            if (text.isEmpty()) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added entry has empty 'text' — skipping",
                    sourceLabel, poolId);
                continue;
            }
            if (text.length() > MAX_ENTRY_TEXT_LEN) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added entry text exceeds {} chars — clamping",
                    sourceLabel, poolId, MAX_ENTRY_TEXT_LEN);
                text = text.substring(0, MAX_ENTRY_TEXT_LEN);
            }
            List<ResourceLocation> itemTypes = readItemTypes(ent.get("item_types"), poolId, sourceLabel);
            entries.addEntry(poolId, new NamePool.PoolEntry(text, itemTypes));
        }
    }

    private static List<ResourceLocation> readItemTypes(JsonElement el, ResourceLocation poolId, String sourceLabel) {
        if (el == null) return List.of();
        if (!el.isJsonArray()) {
            LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added entry item_types is not an array — ignoring",
                sourceLabel, poolId);
            return List.of();
        }
        JsonArray arr = el.getAsJsonArray();
        List<ResourceLocation> out = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added item_types contains a non-string — skipping",
                    sourceLabel, poolId);
                continue;
            }
            ResourceLocation rl = ResourceLocation.tryParse(item.getAsString());
            if (rl == null) {
                LOGGER.warn("[AdventureItemNames] config '{}' pool_entry_overrides['{}'].added item_types entry '{}' is not a valid resource location — skipping",
                    sourceLabel, poolId, item.getAsString());
                continue;
            }
            out.add(rl);
        }
        return List.copyOf(out);
    }

    public static LoadedConfig parse(InputStream in, String sourceLabel) {
        try {
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(root, sourceLabel);
        } catch (Exception e) {
            LOGGER.warn("[AdventureItemNames] config '{}' failed to parse: {}", sourceLabel, e.getMessage());
            return LoadedConfig.empty();
        }
    }

    /** Validate a weight-override key of the form {@code <chain_id>#<segment_index>#<ref_id>}. */
    static boolean isValidWeightKey(String key) {
        if (key == null) return false;
        int firstHash = key.indexOf('#');
        if (firstHash <= 0) return false;
        int secondHash = key.indexOf('#', firstHash + 1);
        if (secondHash <= firstHash + 1) return false;
        if (secondHash == key.length() - 1) return false;
        String chainId = key.substring(0, firstHash);
        String segIdx = key.substring(firstHash + 1, secondHash);
        String refId = key.substring(secondHash + 1);
        if (net.minecraft.resources.ResourceLocation.tryParse(chainId) == null) return false;
        if (net.minecraft.resources.ResourceLocation.tryParse(refId) == null) return false;
        try {
            int idx = Integer.parseInt(segIdx);
            return idx >= 0;
        } catch (NumberFormatException ex) {
            return false;
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
