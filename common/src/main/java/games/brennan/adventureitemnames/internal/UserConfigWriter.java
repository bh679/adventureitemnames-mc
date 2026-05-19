package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Atomic writer for {@code <configDir>/adventureitemnames.json}. Reads
 * any existing content, merges the supplied edits, and writes the result
 * via tmp-file + atomic rename so a crash mid-write can never produce a
 * truncated config.
 *
 * <p>This is the opposite-direction sibling of {@link UserConfigLoader} —
 * the loader pushes the file's content into the runtime layer; the writer
 * pushes UI edits back to the file. The pretty-printed output keeps the
 * file human-editable.
 *
 * <p>Single-threaded by design: callers are the client UI thread on
 * {@code Save to pack}. Concurrent edits from other sources are handled
 * by re-reading the file inside this method before merging.
 */
public final class UserConfigWriter {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "adventureitemnames.json";

    private UserConfigWriter() {}

    /**
     * Merge edits into the on-disk config file. Existing unrelated fields
     * are preserved. Disable-pool toggles are reflected by adding /
     * removing entries from {@code pools[]}; selector toggles similarly
     * touch {@code selectors[]}. Weight overrides go into the
     * {@code weight_overrides} map; passing a negative value removes the
     * override for that key. Pool entry overrides (add / remove on the
     * entries of one pool) are merged into the {@code pool_entry_overrides}
     * object — passing an empty {@link EntryOverrides} leaves the existing
     * field untouched. Chance overrides go into the {@code chances} object;
     * selector tier overrides into {@code selector_overrides}. Passing
     * {@code null} for a value clears that override.
     *
     * @return true on success; false if the config dir is unset or write
     *     failed. The error is logged.
     */
    public static synchronized boolean save(Set<ResourceLocation> disabledPools,
                                            Set<ResourceLocation> enabledPools,
                                            Set<ResourceLocation> disabledSelectors,
                                            Set<ResourceLocation> enabledSelectors,
                                            Map<String, Float> weightOverrides,
                                            EntryOverrides entryOverrides,
                                            Map<ChanceKind, Float> chanceOverrides,
                                            Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> selectorTierOverrides) {
        Path configDir = ConfigPaths.get();
        if (configDir == null) {
            LOGGER.warn("[AdventureItemNames] config dir not set — cannot save user config");
            return false;
        }
        Path file = configDir.resolve(FILE_NAME);
        JsonObject root = readRootOrEmpty(file);

        applyIdListEdits(root, "pools", disabledPools, enabledPools);
        applyIdListEdits(root, "selectors", disabledSelectors, enabledSelectors);
        applyWeightEdits(root, weightOverrides);
        applyEntryEdits(root, entryOverrides);
        applyChanceEdits(root, chanceOverrides);
        applySelectorTierEdits(root, selectorTierOverrides);

        try {
            Files.createDirectories(configDir);
            Path tmp = configDir.resolve(FILE_NAME + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root);
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write {}: {}", file, ex.getMessage());
            return false;
        }
    }

    private static JsonObject readRootOrEmpty(Path file) {
        if (!Files.exists(file)) return new JsonObject();
        try (InputStream in = Files.newInputStream(file)) {
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] existing {} unreadable, starting fresh: {}", file, ex.getMessage());
            return new JsonObject();
        }
    }

    /**
     * Merge disable / enable edits into a top-level id-array (currently
     * used for both {@code pools[]} and {@code selectors[]}). Idempotent —
     * absent edits are a no-op.
     */
    private static void applyIdListEdits(JsonObject root, String key,
                                         Set<ResourceLocation> disable, Set<ResourceLocation> enable) {
        if ((disable == null || disable.isEmpty()) && (enable == null || enable.isEmpty())) return;
        JsonArray existing;
        JsonElement el = root.get(key);
        if (el != null && el.isJsonArray()) {
            existing = el.getAsJsonArray();
        } else {
            existing = new JsonArray();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement item : existing) {
            if (item.isJsonPrimitive()) ids.add(item.getAsString());
        }
        if (disable != null) for (ResourceLocation rl : disable) ids.add(rl.toString());
        if (enable != null) for (ResourceLocation rl : enable) ids.remove(rl.toString());

        JsonArray rebuilt = new JsonArray();
        for (String s : ids) rebuilt.add(s);
        root.add(key, rebuilt);
    }

    /**
     * Merge chance edits into the {@code chances} block. A {@code null} or
     * negative value clears that key. Values equal to {@link ChanceKind#defaultValue}
     * are also removed so the file stays minimal — defaults reapply on next load.
     * If the resulting block is empty, the {@code chances} key is dropped entirely.
     */
    private static void applyChanceEdits(JsonObject root, Map<ChanceKind, Float> edits) {
        if (edits == null || edits.isEmpty()) return;
        JsonObject existing;
        JsonElement el = root.get("chances");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }
        for (var entry : edits.entrySet()) {
            ChanceKind kind = entry.getKey();
            Float v = entry.getValue();
            if (v == null || v < 0f) {
                existing.remove(kind.key());
                continue;
            }
            float clamped = Math.min(1f, v);
            if (clamped == kind.defaultValue()) {
                existing.remove(kind.key());
            } else {
                existing.add(kind.key(), new JsonPrimitive(clamped));
            }
        }
        if (existing.size() == 0) {
            root.remove("chances");
        } else {
            root.add("chances", existing);
        }
    }

    /**
     * Merge selector tier edits into the {@code selector_overrides} block.
     * Entries with {@code null} as the {@link Optional} clear the key. Entries
     * with {@code Optional.empty()} write JSON {@code null} ((none) sentinel).
     * Entries with {@code Optional.of(rl)} write the chain id string. Selectors
     * whose object becomes empty are dropped; the block itself is dropped when
     * empty.
     */
    private static void applySelectorTierEdits(JsonObject root,
                                               Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> edits) {
        if (edits == null || edits.isEmpty()) return;
        JsonObject existing;
        JsonElement el = root.get("selector_overrides");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }
        for (var entry : edits.entrySet()) {
            String selectorKey = entry.getKey().toString();
            JsonObject perTier;
            JsonElement perEl = existing.get(selectorKey);
            if (perEl != null && perEl.isJsonObject()) {
                perTier = perEl.getAsJsonObject();
            } else {
                perTier = new JsonObject();
            }
            for (var tierEntry : entry.getValue().entrySet()) {
                String tierKey = tierEntry.getKey();
                Optional<ResourceLocation> val = tierEntry.getValue();
                if (val == null) {
                    perTier.remove(tierKey);
                } else if (val.isEmpty()) {
                    perTier.add(tierKey, JsonNull.INSTANCE);
                } else {
                    perTier.add(tierKey, new JsonPrimitive(val.get().toString()));
                }
            }
            if (perTier.size() == 0) {
                existing.remove(selectorKey);
            } else {
                existing.add(selectorKey, perTier);
            }
        }
        if (existing.size() == 0) {
            root.remove("selector_overrides");
        } else {
            root.add("selector_overrides", existing);
        }
    }

    private static void applyWeightEdits(JsonObject root, Map<String, Float> edits) {
        if (edits == null || edits.isEmpty()) return;
        JsonObject overrides;
        JsonElement existing = root.get("weight_overrides");
        if (existing != null && existing.isJsonObject()) {
            overrides = existing.getAsJsonObject();
        } else {
            overrides = new JsonObject();
        }
        // Migrate to a sorted map for stable output ordering.
        TreeMap<String, JsonElement> sorted = new TreeMap<>();
        for (var e : overrides.entrySet()) sorted.put(e.getKey(), e.getValue());

        for (var entry : edits.entrySet()) {
            String key = entry.getKey();
            Float v = entry.getValue();
            if (v == null || v < 0f) {
                sorted.remove(key);
            } else {
                sorted.put(key, new JsonPrimitive(v));
            }
        }
        JsonObject rebuilt = new JsonObject();
        for (var e : sorted.entrySet()) rebuilt.add(e.getKey(), e.getValue());
        root.add("weight_overrides", rebuilt);
    }

    /**
     * Merge entry add/remove edits into the {@code pool_entry_overrides}
     * object. Existing per-pool entries (from a previous save) are kept
     * and the supplied {@link EntryOverrides} is unioned in. Empty
     * per-pool bodies are pruned so the file stays tidy.
     */
    private static void applyEntryEdits(JsonObject root, EntryOverrides edits) {
        if (edits == null || edits.isEmpty()) return;
        JsonObject overrides;
        JsonElement existing = root.get("pool_entry_overrides");
        if (existing != null && existing.isJsonObject()) {
            overrides = existing.getAsJsonObject();
        } else {
            overrides = new JsonObject();
        }
        TreeMap<String, JsonObject> sorted = new TreeMap<>();
        for (var e : overrides.entrySet()) {
            if (e.getValue().isJsonObject()) sorted.put(e.getKey(), e.getValue().getAsJsonObject());
        }

        Set<ResourceLocation> touched = new LinkedHashSet<>();
        touched.addAll(edits.removed.keySet());
        touched.addAll(edits.added.keySet());

        for (ResourceLocation poolId : touched) {
            String key = poolId.toString();
            JsonObject body = sorted.computeIfAbsent(key, k -> new JsonObject());
            mergeRemovedTexts(body, edits.removed.get(poolId));
            mergeAddedEntries(body, edits.added.get(poolId));
            // Purge any text that appears in BOTH added and removed — runtime
            // would drop it anyway via the effectivePoolEntries filter, so we
            // keep the file self-consistent. The remove entry is purged
            // first (matching texts in `added` win over `removed`).
            pruneConflicts(body);
            if (isBodyEmpty(body)) sorted.remove(key);
        }

        if (sorted.isEmpty()) {
            root.remove("pool_entry_overrides");
            return;
        }
        JsonObject rebuilt = new JsonObject();
        for (var e : sorted.entrySet()) rebuilt.add(e.getKey(), e.getValue());
        root.add("pool_entry_overrides", rebuilt);
    }

    private static void mergeRemovedTexts(JsonObject body, Set<String> texts) {
        if (texts == null || texts.isEmpty()) return;
        Set<String> all = new LinkedHashSet<>();
        JsonElement existing = body.get("removed");
        if (existing != null && existing.isJsonArray()) {
            for (JsonElement el : existing.getAsJsonArray()) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) all.add(el.getAsString());
            }
        }
        all.addAll(texts);
        JsonArray arr = new JsonArray();
        for (String s : all) arr.add(s);
        body.add("removed", arr);
    }

    private static void mergeAddedEntries(JsonObject body, List<NamePool.PoolEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        JsonArray arr;
        JsonElement existing = body.get("added");
        if (existing != null && existing.isJsonArray()) {
            arr = existing.getAsJsonArray();
        } else {
            arr = new JsonArray();
        }
        // Dedup by text — if the user added "Magenta" twice across sessions, keep one.
        Set<String> existingTexts = new LinkedHashSet<>();
        for (JsonElement el : arr) {
            if (el.isJsonObject() && el.getAsJsonObject().has("text")) {
                JsonElement t = el.getAsJsonObject().get("text");
                if (t.isJsonPrimitive() && t.getAsJsonPrimitive().isString()) existingTexts.add(t.getAsString());
            }
        }
        for (NamePool.PoolEntry entry : entries) {
            if (existingTexts.contains(entry.text())) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("text", entry.text());
            JsonArray types = new JsonArray();
            for (ResourceLocation rl : entry.itemTypes()) types.add(rl.toString());
            obj.add("item_types", types);
            arr.add(obj);
            existingTexts.add(entry.text());
        }
        body.add("added", arr);
    }

    /**
     * Drop any text that's listed in both {@code added[]} and
     * {@code removed[]}. Tactically the user just "re-added" something
     * they previously removed (or vice versa); the most-recent edit
     * wins. Since the writer can't tell which was authored later, the
     * conservative behaviour is to drop both — letting the SHIPPED
     * value (if any) come through. The runtime fall-through covers the
     * fully-blanked-pool case.
     */
    private static void pruneConflicts(JsonObject body) {
        JsonElement removedEl = body.get("removed");
        JsonElement addedEl = body.get("added");
        if (removedEl == null || !removedEl.isJsonArray() || addedEl == null || !addedEl.isJsonArray()) return;
        JsonArray removed = removedEl.getAsJsonArray();
        JsonArray added = addedEl.getAsJsonArray();
        Set<String> removedTexts = new LinkedHashSet<>();
        for (JsonElement el : removed) {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) removedTexts.add(el.getAsString());
        }
        if (removedTexts.isEmpty()) return;
        Set<String> conflicting = new LinkedHashSet<>();
        for (int i = added.size() - 1; i >= 0; i--) {
            JsonElement el = added.get(i);
            if (!el.isJsonObject()) continue;
            JsonElement t = el.getAsJsonObject().get("text");
            if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isString()) continue;
            String text = t.getAsString();
            if (removedTexts.contains(text)) {
                added.remove(i);
                conflicting.add(text);
            }
        }
        if (conflicting.isEmpty()) return;
        JsonArray cleanedRemoved = new JsonArray();
        for (String s : removedTexts) {
            if (!conflicting.contains(s)) cleanedRemoved.add(s);
        }
        if (cleanedRemoved.isEmpty()) {
            body.remove("removed");
        } else {
            body.add("removed", cleanedRemoved);
        }
        if (added.isEmpty()) body.remove("added");
    }

    private static boolean isBodyEmpty(JsonObject body) {
        JsonElement removed = body.get("removed");
        JsonElement added = body.get("added");
        boolean removedEmpty = removed == null || (removed.isJsonArray() && removed.getAsJsonArray().isEmpty());
        boolean addedEmpty = added == null || (added.isJsonArray() && added.getAsJsonArray().isEmpty());
        return removedEmpty && addedEmpty;
    }
}
