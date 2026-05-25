package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Merges UI-driven chance edits into a pack's
 * {@code data/<ns>/chances/defaults.json} on disk in a Loom dev
 * environment. Only meaningful when {@link PackPaths#projectRootAvailable()}
 * returns true — production users have the pack files inside the mod jar
 * and cannot write to them.
 *
 * <p>Schema: a flat {@code { "<chance_kind_key>": <0..1 float> }} map.
 * Loader counterpart is {@link ChanceLoader}; both treat missing keys as
 * "no override → fall through to {@link ChanceKind#defaultValue()}".
 *
 * <p>Read-modify-write: existing keys we don't have an edit for are left
 * alone. Setting a value equal to the kind's shipped default removes the
 * key from the file (keeps diffs small).
 *
 * <p>Tmp-file + atomic-move semantics match {@link PackChainWriter} so a
 * crash mid-write cannot truncate the file.
 */
public final class PackChanceWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackChanceWriter() {}

    /**
     * Apply chance edits to {@code packId}'s {@code chances/defaults.json}
     * file. Returns true on success (including no-op); false when the
     * path can't be resolved or write fails.
     */
    public static boolean writeChances(String packId, Map<ChanceKind, Float> edits) {
        if (edits == null || edits.isEmpty()) return true;
        Path file = PackPaths.chanceFile(packId, "defaults");
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve chance file path for pack '{}'", packId);
            return false;
        }
        JsonObject root = readRootOrEmpty(file);
        boolean changed = false;
        for (Map.Entry<ChanceKind, Float> entry : edits.entrySet()) {
            ChanceKind kind = entry.getKey();
            Float value = entry.getValue();
            if (kind == null) continue;
            String key = kind.key();
            if (value == null || value < 0f) {
                // Negative / null marks a clear → drop the key.
                if (root.has(key)) {
                    root.remove(key);
                    changed = true;
                }
                continue;
            }
            float clamped = Math.max(0f, Math.min(1f, value));
            // If the new value matches the shipped default, drop the key
            // so the file stays diff-clean — the loader will fall through
            // to the default automatically.
            if (Math.abs(clamped - kind.defaultValue()) < 1e-6f) {
                if (root.has(key)) {
                    root.remove(key);
                    changed = true;
                }
                continue;
            }
            JsonElement existing = root.get(key);
            if (existing != null && existing.isJsonPrimitive() && existing.getAsJsonPrimitive().isNumber()
                && Math.abs(existing.getAsFloat() - clamped) < 1e-6f) {
                continue;
            }
            root.add(key, new JsonPrimitive(clamped));
            changed = true;
        }
        if (!changed) return true;

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote chance file for pack '{}' ({})", packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write chance file at '{}': {}", file, ex.getMessage());
            return false;
        }
    }

    private static JsonObject readRootOrEmpty(Path file) {
        if (!Files.exists(file)) return new JsonObject();
        try (InputStream in = Files.newInputStream(file)) {
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] existing chance file '{}' unreadable, recreating: {}", file, ex.getMessage());
            return new JsonObject();
        }
    }
}
