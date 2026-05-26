package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.MobCategory;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *   },
 *   "chances": {
 *     "plain":        0.30,
 *     "enchanted":    0.50,
 *     "mob_passive":  0.05,
 *     "mob_villager": 1.00
 *   },
 *   "selector_overrides": {
 *     "adventureitemnames:sword": {
 *       "plain":     "adventureitemnames:weapon_name_full",
 *       "enchanted": null
 *     }
 *   },
 *   "segment_overrides": {
 *     "adventureitemnames:title_combinations#1": {
 *       "chance":     0.85,
 *       "connection": " of ",
 *       "newline":    false,
 *       "refs": [
 *         { "ref": "adventureitemnames:mc_technoblade", "weight": 10.0 }
 *       ]
 *     }
 *   },
 *   "custom_selectors": {
 *     "adventureitemnames:mace": {
 *       "applies_to": "minecraft:maces",
 *       "tiers": {
 *         "plain":     "adventureitemnames:weapon_name_short",
 *         "enchanted": "adventureitemnames:weapon_name_full"
 *       }
 *     }
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
        ChanceOverrides chances = new ChanceOverrides();
        ColorOverrides colors = new ColorOverrides();
        SelectorOverrides selectorOverrides = new SelectorOverrides();
        SegmentOverrides segmentOverrides = new SegmentOverrides();
        CustomSelectors customSelectors = new CustomSelectors();
        if (root == null || !root.isJsonObject()) {
            if (root != null) {
                LOGGER.warn("[AdventureItemNames] config '{}' root is not a JSON object — ignoring", sourceLabel);
            }
            return new LoadedConfig(disables, weights, entries, chances, colors, selectorOverrides, segmentOverrides, customSelectors);
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

        readChances(obj, chances, sourceLabel);
        readColors(obj, colors, sourceLabel);
        readSelectorOverrides(obj, selectorOverrides, sourceLabel);
        readSegmentOverrides(obj, segmentOverrides, sourceLabel);
        readCustomSelectors(obj, customSelectors, sourceLabel);

        return new LoadedConfig(disables, weights, entries, chances, colors, selectorOverrides, segmentOverrides, customSelectors);
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

    /**
     * Parse the {@code colors} block. Each value is a {@link ChatFormatting}
     * name (e.g. {@code "GOLD"}, {@code "aqua"} — case-insensitive); unknown
     * keys, unknown color names, and non-color formats (bold, italic, reset)
     * are logged and skipped.
     */
    private static void readColors(JsonObject root, ColorOverrides dest, String sourceLabel) {
        JsonElement el = root.get("colors");
        if (el == null) return;
        if (!el.isJsonObject()) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'colors' is not an object — ignoring", sourceLabel);
            return;
        }
        for (var entry : el.getAsJsonObject().entrySet()) {
            ChanceKind kind = ChanceKind.fromKey(entry.getKey());
            if (kind == null) {
                LOGGER.warn("[AdventureItemNames] config '{}' unknown color key '{}'", sourceLabel, entry.getKey());
                continue;
            }
            JsonElement vEl = entry.getValue();
            if (vEl == null || !vEl.isJsonPrimitive() || !vEl.getAsJsonPrimitive().isString()) {
                LOGGER.warn("[AdventureItemNames] config '{}' colors['{}'] is not a string — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            String name = vEl.getAsString();
            ChatFormatting color = ChatFormatting.getByName(name);
            if (color == null || !color.isColor()) {
                LOGGER.warn("[AdventureItemNames] config '{}' colors['{}'] '{}' is not a valid color — ignoring",
                    sourceLabel, entry.getKey(), name);
                continue;
            }
            dest.values.put(kind, color);
        }
    }

    /**
     * Parse the {@code chances} block. Out-of-range entries are clamped
     * to {@code [0, 1]} (with a warning); non-numeric / unknown keys are
     * logged and skipped.
     */
    private static void readChances(JsonObject root, ChanceOverrides dest, String sourceLabel) {
        JsonElement el = root.get("chances");
        if (el == null) return;
        if (!el.isJsonObject()) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'chances' is not an object — ignoring", sourceLabel);
            return;
        }
        for (var entry : el.getAsJsonObject().entrySet()) {
            ChanceKind kind = ChanceKind.fromKey(entry.getKey());
            if (kind == null) {
                LOGGER.warn("[AdventureItemNames] config '{}' unknown chance key '{}'", sourceLabel, entry.getKey());
                continue;
            }
            JsonElement vEl = entry.getValue();
            if (vEl == null || !vEl.isJsonPrimitive() || !vEl.getAsJsonPrimitive().isNumber()) {
                LOGGER.warn("[AdventureItemNames] config '{}' chances['{}'] is not a number — ignoring", sourceLabel, entry.getKey());
                continue;
            }
            float v = vEl.getAsFloat();
            if (v < 0f || v > 1f) {
                LOGGER.warn("[AdventureItemNames] config '{}' chances['{}'] {} out of range [0,1] — clamping",
                    sourceLabel, entry.getKey(), v);
                v = Math.max(0f, Math.min(1f, v));
            }
            dest.values.put(kind, v);
        }
    }

    /**
     * Parse the {@code selector_overrides} block. Per-selector object, per-tier
     * value either a {@link ResourceLocation} string or JSON {@code null} (the
     * {@code (none)} sentinel — naming suppressed for that tier).
     */
    private static void readSelectorOverrides(JsonObject root, SelectorOverrides dest, String sourceLabel) {
        JsonElement el = root.get("selector_overrides");
        if (el == null) return;
        if (!el.isJsonObject()) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'selector_overrides' is not an object — ignoring", sourceLabel);
            return;
        }
        for (var entry : el.getAsJsonObject().entrySet()) {
            ResourceLocation selectorId = ResourceLocation.tryParse(entry.getKey());
            if (selectorId == null) {
                LOGGER.warn("[AdventureItemNames] config '{}' selector_overrides key '{}' is not a valid id",
                    sourceLabel, entry.getKey());
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' selector_overrides['{}'] is not an object — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            for (var tierEntry : entry.getValue().getAsJsonObject().entrySet()) {
                String tierKey = tierEntry.getKey();
                JsonElement tierVal = tierEntry.getValue();
                Optional<ResourceLocation> override;
                if (tierVal == null || tierVal.isJsonNull()) {
                    override = Optional.empty();
                } else if (tierVal.isJsonPrimitive() && tierVal.getAsJsonPrimitive().isString()) {
                    ResourceLocation chainId = ResourceLocation.tryParse(tierVal.getAsString());
                    if (chainId == null) {
                        LOGGER.warn("[AdventureItemNames] config '{}' selector_overrides['{}']['{}'] '{}' is not a valid id — ignoring",
                            sourceLabel, entry.getKey(), tierKey, tierVal.getAsString());
                        continue;
                    }
                    override = Optional.of(chainId);
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' selector_overrides['{}']['{}'] must be a string or null — ignoring",
                        sourceLabel, entry.getKey(), tierKey);
                    continue;
                }
                dest.put(selectorId, tierKey, override);
            }
        }
    }

    /**
     * Parse the {@code segment_overrides} block. Keys are
     * {@code <chain_id>#<segment_index>}. Each entry is an object with
     * optional {@code chance} (float, clamped), {@code connection} (string),
     * {@code newline} (bool), and {@code refs} (array of
     * {@code {ref, weight}} objects — replaces the shipped ref list when
     * present). Empty / fully-null entries are dropped.
     */
    private static void readSegmentOverrides(JsonObject root, SegmentOverrides dest, String sourceLabel) {
        JsonElement el = root.get("segment_overrides");
        if (el == null) return;
        if (!el.isJsonObject()) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'segment_overrides' is not an object — ignoring", sourceLabel);
            return;
        }
        for (var entry : el.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            if (!isValidSegmentKey(key)) {
                LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides key '{}' is malformed — ignoring",
                    sourceLabel, key);
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'] is not an object — ignoring",
                    sourceLabel, key);
                continue;
            }
            JsonObject obj = entry.getValue().getAsJsonObject();
            Float chance = null;
            String connection = null;
            Boolean newline = null;
            List<NameSegment.WeightedRef> refs = null;

            JsonElement chanceEl = obj.get("chance");
            if (chanceEl != null && !chanceEl.isJsonNull()) {
                if (chanceEl.isJsonPrimitive() && chanceEl.getAsJsonPrimitive().isNumber()) {
                    float v = chanceEl.getAsFloat();
                    if (v < 0f || v > 1f) {
                        LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].chance {} out of [0,1] — clamping",
                            sourceLabel, key, v);
                        v = Math.max(0f, Math.min(1f, v));
                    }
                    chance = v;
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].chance is not a number — ignoring",
                        sourceLabel, key);
                }
            }

            JsonElement connEl = obj.get("connection");
            if (connEl != null && !connEl.isJsonNull()) {
                if (connEl.isJsonPrimitive() && connEl.getAsJsonPrimitive().isString()) {
                    connection = connEl.getAsString();
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].connection is not a string — ignoring",
                        sourceLabel, key);
                }
            }

            JsonElement nlEl = obj.get("newline");
            if (nlEl != null && !nlEl.isJsonNull()) {
                if (nlEl.isJsonPrimitive() && nlEl.getAsJsonPrimitive().isBoolean()) {
                    newline = nlEl.getAsBoolean();
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].newline is not a bool — ignoring",
                        sourceLabel, key);
                }
            }

            JsonElement refsEl = obj.get("refs");
            if (refsEl != null && !refsEl.isJsonNull()) {
                if (refsEl.isJsonArray()) {
                    List<NameSegment.WeightedRef> parsed = new ArrayList<>();
                    for (JsonElement r : refsEl.getAsJsonArray()) {
                        if (!r.isJsonObject()) continue;
                        JsonObject rObj = r.getAsJsonObject();
                        JsonElement refStr = rObj.get("ref");
                        JsonElement weightEl = rObj.get("weight");
                        if (refStr == null || !refStr.isJsonPrimitive()) continue;
                        ResourceLocation refId = ResourceLocation.tryParse(refStr.getAsString());
                        if (refId == null) {
                            LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].refs[] '{}' is not a valid id — ignoring",
                                sourceLabel, key, refStr.getAsString());
                            continue;
                        }
                        float w = 1f;
                        if (weightEl != null && weightEl.isJsonPrimitive() && weightEl.getAsJsonPrimitive().isNumber()) {
                            w = Math.max(0f, Math.min(MAX_WEIGHT, weightEl.getAsFloat()));
                        }
                        parsed.add(new NameSegment.WeightedRef(refId, w));
                    }
                    refs = parsed;
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].refs is not an array — ignoring",
                        sourceLabel, key);
                }
            }

            Boolean removed = null;
            JsonElement removedEl = obj.get("removed");
            if (removedEl != null && !removedEl.isJsonNull()) {
                if (removedEl.isJsonPrimitive() && removedEl.getAsJsonPrimitive().isBoolean()) {
                    removed = removedEl.getAsBoolean();
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].removed is not a bool — ignoring",
                        sourceLabel, key);
                }
            }

            String label = null;
            JsonElement labelEl = obj.get("label");
            if (labelEl != null && !labelEl.isJsonNull()) {
                if (labelEl.isJsonPrimitive() && labelEl.getAsJsonPrimitive().isString()) {
                    label = labelEl.getAsString();
                } else {
                    LOGGER.warn("[AdventureItemNames] config '{}' segment_overrides['{}'].label is not a string — ignoring",
                        sourceLabel, key);
                }
            }

            SegmentOverrides.SegmentEdit edit = new SegmentOverrides.SegmentEdit(chance, connection, newline, refs, removed, label);
            if (!edit.isNoOp()) dest.edits.put(key, edit);
        }

        // Segment-order block: per-chain permutation of original indices that
        // defines the display / composer iteration order. Length must match
        // the chain's current effective segment count or the override is
        // silently ignored at query time.
        JsonElement orderEl = root.get("segment_order");
        if (orderEl != null) {
            if (!orderEl.isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' 'segment_order' is not an object — ignoring", sourceLabel);
            } else {
                for (var entry : orderEl.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonArray()) {
                        LOGGER.warn("[AdventureItemNames] config '{}' segment_order['{}'] is not an array — ignoring",
                            sourceLabel, entry.getKey());
                        continue;
                    }
                    List<Integer> indices = new ArrayList<>();
                    boolean valid = true;
                    for (JsonElement el2 : entry.getValue().getAsJsonArray()) {
                        if (!el2.isJsonPrimitive() || !el2.getAsJsonPrimitive().isNumber()) { valid = false; break; }
                        int idx = el2.getAsInt();
                        if (idx < 0) { valid = false; break; }
                        indices.add(idx);
                    }
                    if (!valid) {
                        LOGGER.warn("[AdventureItemNames] config '{}' segment_order['{}'] contains non-integer or negative entries — ignoring",
                            sourceLabel, entry.getKey());
                        continue;
                    }
                    if (!indices.isEmpty()) dest.segmentOrder.put(entry.getKey(), indices);
                }
            }
        }

        // Appended-segments block: per-chain ordered list of brand-new segments
        // the UI tacked on after the shipped tail. Each appended segment is a
        // full NameSegment record (refs + chance + connection + newline).
        JsonElement appendedEl = root.get("appended_segments");
        if (appendedEl != null) {
            if (!appendedEl.isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' 'appended_segments' is not an object — ignoring", sourceLabel);
            } else {
                for (var entry : appendedEl.getAsJsonObject().entrySet()) {
                    ResourceLocation chainId = ResourceLocation.tryParse(entry.getKey());
                    if (chainId == null) {
                        LOGGER.warn("[AdventureItemNames] config '{}' appended_segments key '{}' is not a valid id — ignoring",
                            sourceLabel, entry.getKey());
                        continue;
                    }
                    if (!entry.getValue().isJsonArray()) {
                        LOGGER.warn("[AdventureItemNames] config '{}' appended_segments['{}'] is not an array — ignoring",
                            sourceLabel, entry.getKey());
                        continue;
                    }
                    List<NameSegment> segs = new ArrayList<>();
                    for (JsonElement segEl : entry.getValue().getAsJsonArray()) {
                        if (!segEl.isJsonObject()) continue;
                        JsonObject segObj = segEl.getAsJsonObject();
                        float segChance = 1f;
                        JsonElement segChanceEl = segObj.get("chance");
                        if (segChanceEl != null && segChanceEl.isJsonPrimitive() && segChanceEl.getAsJsonPrimitive().isNumber()) {
                            segChance = Math.max(0f, Math.min(1f, segChanceEl.getAsFloat()));
                        }
                        String segConn = "";
                        JsonElement segConnEl = segObj.get("connection");
                        if (segConnEl != null && segConnEl.isJsonPrimitive() && segConnEl.getAsJsonPrimitive().isString()) {
                            segConn = segConnEl.getAsString();
                        }
                        boolean segNl = false;
                        JsonElement segNlEl = segObj.get("newline");
                        if (segNlEl != null && segNlEl.isJsonPrimitive() && segNlEl.getAsJsonPrimitive().isBoolean()) {
                            segNl = segNlEl.getAsBoolean();
                        }
                        List<NameSegment.WeightedRef> segRefs = new ArrayList<>();
                        JsonElement segRefsEl = segObj.get("refs");
                        if (segRefsEl != null && segRefsEl.isJsonArray()) {
                            for (JsonElement r : segRefsEl.getAsJsonArray()) {
                                if (!r.isJsonObject()) continue;
                                JsonObject rObj = r.getAsJsonObject();
                                JsonElement refStr = rObj.get("ref");
                                if (refStr == null || !refStr.isJsonPrimitive()) continue;
                                ResourceLocation refId = ResourceLocation.tryParse(refStr.getAsString());
                                if (refId == null) continue;
                                float w = 1f;
                                JsonElement weightEl = rObj.get("weight");
                                if (weightEl != null && weightEl.isJsonPrimitive() && weightEl.getAsJsonPrimitive().isNumber()) {
                                    w = Math.max(0f, Math.min(MAX_WEIGHT, weightEl.getAsFloat()));
                                }
                                segRefs.add(new NameSegment.WeightedRef(refId, w));
                            }
                        }
                        segs.add(new NameSegment(segRefs, segChance, segConn, segNl));
                    }
                    if (!segs.isEmpty()) {
                        dest.appendedSegments.put(entry.getKey(), segs);
                    }
                }
            }
        }
    }

    /**
     * Parse the {@code custom_selectors} block. Each top-level key is a
     * selector id (must parse as a valid {@link ResourceLocation}); each
     * value is an object with {@code applies_to} (item-tag id) and
     * {@code tiers} (map of tier-key → chain id). Malformed entries log
     * a warning and are skipped.
     */
    private static void readCustomSelectors(JsonObject root, CustomSelectors dest, String sourceLabel) {
        JsonElement el = root.get("custom_selectors");
        if (el == null) return;
        if (!el.isJsonObject()) {
            LOGGER.warn("[AdventureItemNames] config '{}' 'custom_selectors' is not an object — ignoring", sourceLabel);
            return;
        }
        for (var entry : el.getAsJsonObject().entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) {
                LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors key '{}' is not a valid id — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            if (!entry.getValue().isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'] is not an object — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            JsonObject obj = entry.getValue().getAsJsonObject();
            JsonElement appliesEl = obj.get("applies_to");
            if (appliesEl == null || !appliesEl.isJsonPrimitive()) {
                LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'].applies_to missing — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            ResourceLocation appliesTo = ResourceLocation.tryParse(appliesEl.getAsString());
            if (appliesTo == null) {
                LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'].applies_to '{}' is not a valid id — ignoring",
                    sourceLabel, entry.getKey(), appliesEl.getAsString());
                continue;
            }
            JsonElement tiersEl = obj.get("tiers");
            if (tiersEl == null || !tiersEl.isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'].tiers missing or not an object — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
            for (var tierEntry : tiersEl.getAsJsonObject().entrySet()) {
                JsonElement v = tierEntry.getValue();
                if (v == null || v.isJsonNull()) continue;
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                    LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'].tiers['{}'] not a string — ignoring",
                        sourceLabel, entry.getKey(), tierEntry.getKey());
                    continue;
                }
                ResourceLocation chainId = ResourceLocation.tryParse(v.getAsString());
                if (chainId == null) {
                    LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'].tiers['{}'] '{}' is not a valid id — ignoring",
                        sourceLabel, entry.getKey(), tierEntry.getKey(), v.getAsString());
                    continue;
                }
                tiers.put(tierEntry.getKey(), chainId);
            }
            if (tiers.isEmpty()) {
                LOGGER.warn("[AdventureItemNames] config '{}' custom_selectors['{}'] has no valid tiers — ignoring",
                    sourceLabel, entry.getKey());
                continue;
            }
            dest.put(new NameSelector(id, appliesTo, tiers));
        }
    }

    /** Validate a segment-override key of the form {@code <chain_id>#<segment_index>}. */
    static boolean isValidSegmentKey(String key) {
        if (key == null) return false;
        int hash = key.indexOf('#');
        if (hash <= 0 || hash == key.length() - 1) return false;
        if (key.indexOf('#', hash + 1) >= 0) return false;
        String chainId = key.substring(0, hash);
        String segIdx = key.substring(hash + 1);
        if (ResourceLocation.tryParse(chainId) == null) return false;
        try {
            int idx = Integer.parseInt(segIdx);
            return idx >= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
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
