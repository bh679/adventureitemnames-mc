package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

/**
 * Writer for selector JSON files under {@code data/<ns>/naming/selectors/}.
 * Two operations:
 *
 * <ul>
 *   <li><b>{@link #writeSelector}</b> — create or rewrite a selector file.
 *       Used both for brand-new custom selectors and for baking tier
 *       overrides into a shipped selector.</li>
 *   <li><b>{@link #deleteSelector}</b> — remove a selector file. Used when
 *       a custom selector is deleted from the UI.</li>
 * </ul>
 *
 * <p>Tier overrides with {@link Optional#empty()} represent the
 * {@code (none)} sentinel — naming suppressed for that tier. The serializer
 * <em>omits</em> these tiers entirely; the loader treats missing tiers as
 * "no chain bound", which produces the same suppression behaviour via
 * {@link games.brennan.adventureitemnames.api.NamingConfig#effectiveTierChain}.
 *
 * <p>Atomic write semantics mirror {@link PackChainWriter}.
 */
public final class PackSelectorWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackSelectorWriter() {}

    /**
     * Write the selector file for {@code id} under {@code packId}.
     * Existing fields not managed by this writer
     * ({@code id}, {@code applies_to}, {@code tiers}) are NOT preserved —
     * the file is fully rewritten from {@code selector}.
     */
    public static boolean writeSelector(String packId, NameSelector selector) {
        if (selector == null) return false;
        Path file = PackPaths.selectorFile(packId, selector.id().getPath());
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve selector file path for pack '{}' selector {}",
                packId, selector.id());
            return false;
        }
        JsonObject root = buildSelectorJson(selector);

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote selector {} to pack '{}' ({})", selector.id(), packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write selector {} to pack '{}': {}",
                selector.id(), packId, ex.getMessage());
            return false;
        }
    }

    /**
     * Apply tier-override edits to an existing selector file, preserving
     * its {@code applies_to}. The {@code tierEdits} map's
     * {@link Optional#empty()} values omit the tier from the rewritten
     * file (= suppression at runtime).
     */
    /**
     * Prefix that distinguishes a description-tier override from a
     * name-tier override in {@link Map#keySet() tierEdits}. {@code
     * description_plain} / {@code description_enchanted} are routed to
     * the selector JSON's {@code description_tiers} block (with the
     * prefix stripped); other keys go to the {@code tiers} block.
     */
    private static final String DESC_PREFIX = "description_";

    public static boolean writeSelectorTiers(String packId, ResourceLocation selectorId,
                                              Map<String, Optional<ResourceLocation>> tierEdits) {
        if (tierEdits == null || tierEdits.isEmpty()) return true;
        Path file = PackPaths.selectorFile(packId, selectorId.getPath());
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve selector file path for pack '{}' selector {}",
                packId, selectorId);
            return false;
        }
        JsonObject root = readRootOrEmpty(file);
        if (!root.has("id")) root.add("id", new JsonPrimitive(selectorId.toString()));
        JsonObject tiers = readOrInitObject(root, "tiers");
        JsonObject descTiers = readOrInitObject(root, "description_tiers");
        for (Map.Entry<String, Optional<ResourceLocation>> e : tierEdits.entrySet()) {
            String key = e.getKey();
            Optional<ResourceLocation> value = e.getValue();
            boolean isDesc = key.startsWith(DESC_PREFIX);
            JsonObject target = isDesc ? descTiers : tiers;
            String jsonKey = isDesc ? key.substring(DESC_PREFIX.length()) : key;
            if (value == null || value.isEmpty()) {
                target.remove(jsonKey);
            } else {
                target.add(jsonKey, new JsonPrimitive(value.get().toString()));
            }
        }
        root.add("tiers", tiers);
        if (descTiers.size() > 0) {
            root.add("description_tiers", descTiers);
        } else {
            root.remove("description_tiers");
        }

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote selector tiers for {} to pack '{}' ({})", selectorId, packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write selector tiers for {} to pack '{}': {}",
                selectorId, packId, ex.getMessage());
            return false;
        }
    }

    /** Delete a selector file. Silent on missing files. */
    public static boolean deleteSelector(String packId, ResourceLocation selectorId) {
        Path file = PackPaths.selectorFile(packId, selectorId.getPath());
        if (file == null) return false;
        try {
            Files.deleteIfExists(file);
            LOGGER.info("[AdventureItemNames] deleted selector {} from pack '{}'", selectorId, packId);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to delete selector file at '{}': {}", file, ex.getMessage());
            return false;
        }
    }

    /**
     * Build the JSON form of {@code selector} in the layout {@link NameCodec}
     * parses. Package-private for unit-test round-trip coverage; production
     * callers should go through {@link #writeSelector}.
     */
    static JsonObject buildSelectorJson(NameSelector selector) {
        JsonObject root = new JsonObject();
        root.add("id", new JsonPrimitive(selector.id().toString()));
        root.add("applies_to", new JsonPrimitive(selector.appliesTo().toString()));
        root.add("tiers", tierMapToJson(selector.tiers()));
        if (!selector.descriptionTiers().isEmpty()) {
            JsonObject descTiers = tierMapToJson(selector.descriptionTiers());
            if (descTiers.size() > 0) root.add("description_tiers", descTiers);
        }
        return root;
    }

    private static JsonObject tierMapToJson(Map<String, ResourceLocation> tiers) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, ResourceLocation> e : tiers.entrySet()) {
            if (e.getValue() == null) continue; // (none) sentinel → omit key
            obj.add(e.getKey(), new JsonPrimitive(e.getValue().toString()));
        }
        return obj;
    }

    /**
     * Get the JSON object stored under {@code key} in {@code root}, or
     * an empty object if {@code root} doesn't carry that key. Used by
     * {@link #writeSelectorTiers} to extend the {@code tiers} and
     * {@code description_tiers} blocks in place without losing entries
     * the caller didn't pass an edit for.
     */
    private static JsonObject readOrInitObject(JsonObject root, String key) {
        JsonElement el = root.get(key);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject readRootOrEmpty(Path file) {
        if (!Files.exists(file)) return new JsonObject();
        try (InputStream in = Files.newInputStream(file)) {
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] existing selector file '{}' unreadable, recreating: {}", file, ex.getMessage());
            return new JsonObject();
        }
    }
}
