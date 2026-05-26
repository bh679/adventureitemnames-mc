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
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.ChatFormatting;
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
                                            Map<ChanceKind, ChatFormatting> colorOverrides,
                                            Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> selectorTierOverrides,
                                            Map<String, SegmentOverrides.SegmentEdit> segmentOverrides,
                                            Set<String> segmentResetKeys,
                                            Map<String, java.util.List<NameSegment>> appendedSegments,
                                            Map<String, java.util.List<Integer>> segmentOrder,
                                            Map<ResourceLocation, NameSelector> customSelectorEdits,
                                            Set<ResourceLocation> removedCustomSelectorIds) {
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
        applyColorEdits(root, colorOverrides);
        applySelectorTierEdits(root, selectorTierOverrides);
        applySegmentEdits(root, segmentOverrides, segmentResetKeys);
        applyAppendedSegments(root, appendedSegments);
        applySegmentOrder(root, segmentOrder);
        applyCustomSelectorEdits(root, customSelectorEdits, removedCustomSelectorIds);

        try {
            Files.createDirectories(configDir);
            Path tmp = configDir.resolve(FILE_NAME + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write {}: {}", file, ex.getMessage());
            return false;
        }
    }

    /**
     * Strip every segment-related entry for {@code chains} from the
     * on-disk user-config file: {@code segment_overrides[<chainId>#*]},
     * {@code appended_segments[<chainId>]}, and {@code segment_order[<chainId>]}.
     * Called after a successful dev-mode pack write so the user-config
     * layer stops double-applying on top of the freshly baked-in pack
     * files. No-op when nothing changes.
     */
    public static synchronized boolean wipeChainSegmentData(Set<ResourceLocation> chains) {
        if (chains == null || chains.isEmpty()) return true;
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);
        boolean changed = false;

        // segment_overrides — keyed by "<chainId>#<segIdx>"; drop entries whose
        // chain id is in the wipe set.
        JsonElement segEl = root.get("segment_overrides");
        if (segEl != null && segEl.isJsonObject()) {
            JsonObject obj = segEl.getAsJsonObject();
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (var entry : obj.entrySet()) {
                int hash = entry.getKey().indexOf('#');
                if (hash <= 0) continue;
                ResourceLocation chainId = ResourceLocation.tryParse(entry.getKey().substring(0, hash));
                if (chainId != null && chains.contains(chainId)) toRemove.add(entry.getKey());
            }
            if (!toRemove.isEmpty()) {
                for (String k : toRemove) obj.remove(k);
                changed = true;
                if (obj.size() == 0) root.remove("segment_overrides");
            }
        }

        // appended_segments and segment_order are both keyed by chain id directly.
        for (String key : new String[]{"appended_segments", "segment_order"}) {
            JsonElement el = root.get(key);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                boolean blockChanged = false;
                for (ResourceLocation chain : chains) {
                    if (obj.has(chain.toString())) {
                        obj.remove(chain.toString());
                        blockChanged = true;
                    }
                }
                if (blockChanged) {
                    changed = true;
                    if (obj.size() == 0) root.remove(key);
                }
            }
        }

        if (!changed) return true;
        try {
            Path tmp = configDir.resolve(FILE_NAME + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wiped user-config segment data for {} chain(s)", chains.size());
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write {} during wipe: {}", file, ex.getMessage());
            return false;
        }
    }

    /**
     * Strip {@code pool_entry_overrides[<poolId>]} for every id in
     * {@code pools} from the on-disk user-config file. Called after a
     * successful dev-mode {@link PackPoolWriter#writePool} so the
     * user-config overlay stops double-applying on top of the freshly
     * baked-in pool file. Sibling of {@link #wipeChainSegmentData}; same
     * no-op-on-empty / atomic-write semantics.
     */
    public static synchronized boolean wipePoolEntryData(Set<ResourceLocation> pools) {
        if (pools == null || pools.isEmpty()) return true;
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);

        JsonElement overridesEl = root.get("pool_entry_overrides");
        if (overridesEl == null || !overridesEl.isJsonObject()) return true;
        JsonObject overrides = overridesEl.getAsJsonObject();
        boolean changed = false;
        for (ResourceLocation pool : pools) {
            if (overrides.has(pool.toString())) {
                overrides.remove(pool.toString());
                changed = true;
            }
        }
        if (!changed) return true;
        if (overrides.size() == 0) root.remove("pool_entry_overrides");

        try {
            Path tmp = configDir.resolve(FILE_NAME + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wiped user-config pool_entry_overrides for {} pool(s)", pools.size());
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write {} during pool wipe: {}", file, ex.getMessage());
            return false;
        }
    }

    /**
     * Strip {@code pools[]} + {@code selectors[]} entries for every id in
     * the wipe sets from the on-disk user-config file. Called after a
     * successful dev-mode {@link PackDisableWriter#writeDisables} so the
     * user-config layer stops double-applying on top of the freshly
     * baked-in {@code disabled/defaults.json}. No-op when nothing changes.
     */
    public static synchronized boolean wipeDisableData(Set<ResourceLocation> disabledPools,
                                                       Set<ResourceLocation> enabledPools,
                                                       Set<ResourceLocation> disabledSelectors,
                                                       Set<ResourceLocation> enabledSelectors) {
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);
        boolean changed = false;
        changed |= wipeIdArrayEntries(root, "pools",     disabledPools,     enabledPools);
        changed |= wipeIdArrayEntries(root, "selectors", disabledSelectors, enabledSelectors);
        if (!changed) return true;
        return atomicWrite(configDir, file, root, "wiped user-config disables");
    }

    /**
     * Strip {@code weight_overrides[<chain>#<seg>#<ref>]} entries whose
     * chain id is in {@code chains} from the on-disk user-config file.
     * Called after a successful dev-mode chain write so user-config weight
     * overlays stop double-applying on top of the freshly baked-in chain
     * JSON. No-op when nothing changes.
     */
    public static synchronized boolean wipeWeightData(Set<ResourceLocation> chains) {
        if (chains == null || chains.isEmpty()) return true;
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);
        JsonElement el = root.get("weight_overrides");
        if (el == null || !el.isJsonObject()) return true;
        JsonObject obj = el.getAsJsonObject();
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (var entry : obj.entrySet()) {
            String key = entry.getKey();
            int firstHash = key.indexOf('#');
            if (firstHash <= 0) continue;
            ResourceLocation chainId = ResourceLocation.tryParse(key.substring(0, firstHash));
            if (chainId != null && chains.contains(chainId)) toRemove.add(key);
        }
        if (toRemove.isEmpty()) return true;
        for (String k : toRemove) obj.remove(k);
        if (obj.size() == 0) root.remove("weight_overrides");
        return atomicWrite(configDir, file, root, "wiped user-config weight overrides");
    }

    /**
     * Strip {@code chances[<kind>]} entries for every {@code kind} in
     * {@code kinds} from the on-disk user-config file. Called after a
     * successful dev-mode {@link PackChanceWriter#writeChances} so the
     * user-config chance overlay stops double-applying on top of the
     * freshly baked-in chance file. No-op when nothing changes.
     */
    public static synchronized boolean wipeChanceData(Set<ChanceKind> kinds) {
        if (kinds == null || kinds.isEmpty()) return true;
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);
        JsonElement el = root.get("chances");
        if (el == null || !el.isJsonObject()) return true;
        JsonObject obj = el.getAsJsonObject();
        boolean changed = false;
        for (ChanceKind k : kinds) {
            if (obj.has(k.key())) {
                obj.remove(k.key());
                changed = true;
            }
        }
        if (!changed) return true;
        if (obj.size() == 0) root.remove("chances");
        return atomicWrite(configDir, file, root, "wiped user-config chance overrides");
    }

    /**
     * Strip {@code selector_overrides[<selectorId>]} entries for every id
     * in {@code selectors} from the on-disk user-config file. Called after
     * a successful dev-mode {@link PackSelectorWriter#writeSelectorTiers}
     * so the user-config selector tier overlay stops double-applying.
     */
    public static synchronized boolean wipeSelectorTierData(Set<ResourceLocation> selectors) {
        if (selectors == null || selectors.isEmpty()) return true;
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);
        JsonElement el = root.get("selector_overrides");
        if (el == null || !el.isJsonObject()) return true;
        JsonObject obj = el.getAsJsonObject();
        boolean changed = false;
        for (ResourceLocation sel : selectors) {
            if (obj.has(sel.toString())) {
                obj.remove(sel.toString());
                changed = true;
            }
        }
        if (!changed) return true;
        if (obj.size() == 0) root.remove("selector_overrides");
        return atomicWrite(configDir, file, root, "wiped user-config selector tier overrides");
    }

    /**
     * Strip {@code custom_selectors[<id>]} entries for every id in
     * {@code ids} from the on-disk user-config file. Called after a
     * successful dev-mode {@link PackSelectorWriter#writeSelector} (for
     * added custom selectors) or {@link PackSelectorWriter#deleteSelector}
     * (for removed ones) so the user-config custom-selector overlay
     * doesn't keep re-installing them.
     */
    public static synchronized boolean wipeCustomSelectorData(Set<ResourceLocation> ids) {
        if (ids == null || ids.isEmpty()) return true;
        Path configDir = ConfigPaths.get();
        if (configDir == null) return true;
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return true;
        JsonObject root = readRootOrEmpty(file);
        JsonElement el = root.get("custom_selectors");
        if (el == null || !el.isJsonObject()) return true;
        JsonObject obj = el.getAsJsonObject();
        boolean changed = false;
        for (ResourceLocation id : ids) {
            if (obj.has(id.toString())) {
                obj.remove(id.toString());
                changed = true;
            }
        }
        if (!changed) return true;
        if (obj.size() == 0) root.remove("custom_selectors");
        return atomicWrite(configDir, file, root, "wiped user-config custom selectors");
    }

    /**
     * Helper used by the wipe-data methods. Mutates {@code root[key]} to
     * remove any ids in {@code removed} or {@code added}. Returns true
     * when the array's contents changed.
     */
    private static boolean wipeIdArrayEntries(JsonObject root, String key,
                                              Set<ResourceLocation> a, Set<ResourceLocation> b) {
        JsonElement el = root.get(key);
        if (el == null || !el.isJsonArray()) return false;
        JsonArray existing = el.getAsJsonArray();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement item : existing) {
            if (item.isJsonPrimitive()) ids.add(item.getAsString());
        }
        boolean changed = false;
        if (a != null) for (ResourceLocation rl : a) if (ids.remove(rl.toString())) changed = true;
        if (b != null) for (ResourceLocation rl : b) if (ids.remove(rl.toString())) changed = true;
        if (!changed) return false;
        if (ids.isEmpty()) {
            root.remove(key);
        } else {
            JsonArray rebuilt = new JsonArray();
            for (String s : ids) rebuilt.add(s);
            root.add(key, rebuilt);
        }
        return true;
    }

    /** Shared atomic tmp-file write + rename, used by every wipe-data method. */
    private static boolean atomicWrite(Path dir, Path file, JsonObject root, String logMsg) {
        try {
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] {} ({})", logMsg, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write {} during wipe: {}", file, ex.getMessage());
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
     * Merge color edits into the {@code colors} block. A {@code null} value
     * clears that key (no color override → default styling). If the resulting
     * block is empty, the {@code colors} key is dropped entirely.
     */
    private static void applyColorEdits(JsonObject root, Map<ChanceKind, ChatFormatting> edits) {
        if (edits == null || edits.isEmpty()) return;
        JsonObject existing;
        JsonElement el = root.get("colors");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }
        for (var entry : edits.entrySet()) {
            ChanceKind kind = entry.getKey();
            ChatFormatting color = entry.getValue();
            if (color == null) {
                existing.remove(kind.key());
            } else {
                existing.add(kind.key(), new JsonPrimitive(color.getName()));
            }
        }
        if (existing.size() == 0) {
            root.remove("colors");
        } else {
            root.add("colors", existing);
        }
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

    /**
     * Merge segment edits into the {@code segment_overrides} block.
     * A {@code null} {@link SegmentOverrides.SegmentEdit} clears the entire
     * key. Within an edit, a {@code null} field removes that override
     * (lets it fall through); a non-null field writes it. A
     * {@link SegmentOverrides.SegmentEdit#isNoOp()} edit drops the key.
     * If the resulting block is empty it's removed.
     */
    /**
     * Merge per-segment edits into {@code segment_overrides}. Two distinct
     * semantics here, both critical:
     *
     * <ul>
     *   <li><b>Resets</b> ({@code resetKeys}) delete the entire
     *       {@code segment_overrides[key]} object — reverts that segment to
     *       its shipped behaviour wholesale.</li>
     *   <li><b>Edits</b> only touch the fields the buffer explicitly set
     *       (i.e. non-null on the {@link SegmentOverrides.SegmentEdit}).
     *       A {@code null} field means "the user didn't touch this in
     *       this session" — leave whatever's already on disk alone. The
     *       previous "null → remove from JSON" semantics destroyed
     *       previously-saved sibling fields whenever the user edited just
     *       one thing.</li>
     * </ul>
     */
    private static void applySegmentEdits(JsonObject root,
                                          Map<String, SegmentOverrides.SegmentEdit> edits,
                                          Set<String> resetKeys) {
        boolean haveResets = resetKeys != null && !resetKeys.isEmpty();
        boolean haveEdits = edits != null && !edits.isEmpty();
        if (!haveResets && !haveEdits) return;
        JsonObject existing;
        JsonElement el = root.get("segment_overrides");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }

        // Process resets first so a reset+edit in the same session ends up
        // with just the new edit content (no stale fields from the prior save).
        if (haveResets) {
            for (String key : resetKeys) existing.remove(key);
        }

        if (haveEdits) {
            for (var entry : edits.entrySet()) {
                String key = entry.getKey();
                SegmentOverrides.SegmentEdit edit = entry.getValue();
                if (edit == null || edit.isNoOp()) continue;
                JsonObject perSeg;
                JsonElement perEl = existing.get(key);
                if (perEl != null && perEl.isJsonObject()) {
                    perSeg = perEl.getAsJsonObject();
                } else {
                    perSeg = new JsonObject();
                }
                if (edit.chance() != null)     perSeg.add("chance", new JsonPrimitive(edit.chance()));
                if (edit.connection() != null) perSeg.add("connection", new JsonPrimitive(edit.connection()));
                if (edit.newline() != null)    perSeg.add("newline", new JsonPrimitive(edit.newline()));
                if (edit.label() != null)      perSeg.add("label", new JsonPrimitive(edit.label()));
                if (Boolean.TRUE.equals(edit.removed())) {
                    perSeg.add("removed", new JsonPrimitive(true));
                }
                if (edit.refs() != null) {
                    JsonArray arr = new JsonArray();
                    for (NameSegment.WeightedRef r : edit.refs()) {
                        JsonObject rObj = new JsonObject();
                        rObj.add("ref", new JsonPrimitive(r.ref().toString()));
                        rObj.add("weight", new JsonPrimitive(r.weight()));
                        arr.add(rObj);
                    }
                    perSeg.add("refs", arr);
                }
                if (perSeg.size() == 0) {
                    existing.remove(key);
                } else {
                    existing.add(key, perSeg);
                }
            }
        }

        if (existing.size() == 0) {
            root.remove("segment_overrides");
        } else {
            root.add("segment_overrides", existing);
        }
    }

    /**
     * Append pending session segments onto the on-disk
     * {@code appended_segments} array for each chain. Buffer pending list
     * is a <em>delta</em> (only this session's adds), not a snapshot, so
     * we extend the existing array instead of replacing it. Empty input
     * map → no-op (leave the block intact).
     */
    private static void applyAppendedSegments(JsonObject root, Map<String, java.util.List<NameSegment>> appended) {
        if (appended == null || appended.isEmpty()) return;
        JsonObject existing;
        JsonElement el = root.get("appended_segments");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }
        for (var entry : appended.entrySet()) {
            java.util.List<NameSegment> segs = entry.getValue();
            if (segs == null || segs.isEmpty()) continue;
            JsonArray arr;
            JsonElement existingArr = existing.get(entry.getKey());
            if (existingArr != null && existingArr.isJsonArray()) {
                arr = existingArr.getAsJsonArray();
            } else {
                arr = new JsonArray();
            }
            for (NameSegment seg : segs) {
                JsonObject segObj = new JsonObject();
                segObj.add("chance", new JsonPrimitive(seg.chance()));
                segObj.add("connection", new JsonPrimitive(seg.connection()));
                segObj.add("newline", new JsonPrimitive(seg.newline()));
                JsonArray refsArr = new JsonArray();
                for (NameSegment.WeightedRef r : seg.refs()) {
                    JsonObject rObj = new JsonObject();
                    rObj.add("ref", new JsonPrimitive(r.ref().toString()));
                    rObj.add("weight", new JsonPrimitive(r.weight()));
                    refsArr.add(rObj);
                }
                segObj.add("refs", refsArr);
                arr.add(segObj);
            }
            existing.add(entry.getKey(), arr);
        }
        if (existing.size() == 0) {
            root.remove("appended_segments");
        } else {
            root.add("appended_segments", existing);
        }
    }

    /**
     * Merge custom-selector edits into the {@code custom_selectors} block.
     * Entries in {@code edits} are upserted; ids in {@code removedIds} are
     * dropped. If the resulting block is empty it's removed.
     */
    private static void applyCustomSelectorEdits(JsonObject root,
                                                 Map<ResourceLocation, NameSelector> edits,
                                                 Set<ResourceLocation> removedIds) {
        boolean hasAny = (edits != null && !edits.isEmpty()) || (removedIds != null && !removedIds.isEmpty());
        if (!hasAny) return;
        JsonObject existing;
        JsonElement el = root.get("custom_selectors");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }
        if (removedIds != null) {
            for (ResourceLocation id : removedIds) existing.remove(id.toString());
        }
        if (edits != null) {
            for (var entry : edits.entrySet()) {
                NameSelector sel = entry.getValue();
                JsonObject sObj = new JsonObject();
                sObj.add("applies_to", new JsonPrimitive(sel.appliesTo().toString()));
                JsonObject tObj = new JsonObject();
                for (var t : sel.tiers().entrySet()) {
                    tObj.add(t.getKey(), new JsonPrimitive(t.getValue().toString()));
                }
                sObj.add("tiers", tObj);
                existing.add(entry.getKey().toString(), sObj);
            }
        }
        if (existing.size() == 0) {
            root.remove("custom_selectors");
        } else {
            root.add("custom_selectors", existing);
        }
    }

    /**
     * Write the {@code segment_order} block — per-chain permutation of
     * original indices. Empty list → drop that chain's entry. Empty
     * input map → leave any existing block alone (caller didn't touch
     * order this session).
     */
    private static void applySegmentOrder(JsonObject root, Map<String, java.util.List<Integer>> order) {
        if (order == null || order.isEmpty()) return;
        JsonObject existing;
        JsonElement el = root.get("segment_order");
        if (el != null && el.isJsonObject()) {
            existing = el.getAsJsonObject();
        } else {
            existing = new JsonObject();
        }
        for (var entry : order.entrySet()) {
            java.util.List<Integer> indices = entry.getValue();
            if (indices == null || indices.isEmpty()) {
                existing.remove(entry.getKey());
                continue;
            }
            JsonArray arr = new JsonArray();
            for (Integer i : indices) arr.add(new JsonPrimitive(i));
            existing.add(entry.getKey(), arr);
        }
        if (existing.size() == 0) {
            root.remove("segment_order");
        } else {
            root.add("segment_order", existing);
        }
    }

    /** If {@code v} is null, remove {@code key}; otherwise add the encoded value. */
    private static <T> void applyOrRemove(JsonObject target, String key, T v,
                                          java.util.function.Function<T, JsonElement> encode) {
        if (v == null) target.remove(key);
        else target.add(key, encode.apply(v));
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
