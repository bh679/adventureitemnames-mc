package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.compat.Colors;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import net.minecraft.ChatFormatting;
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
 * Merges UI-driven color edits into a pack's
 * {@code data/<ns>/colors/defaults.json} on disk in a Loom dev
 * environment. Only meaningful when {@link PackPaths#projectRootAvailable()}
 * returns true — production users have the pack files inside the mod jar
 * and cannot write to them.
 *
 * <p>Schema: a flat {@code { "<chance_kind_key>": "<chat_formatting_name>" }}
 * map. Loader counterpart is {@link ColorLoader}; both treat missing keys
 * as "no override → fall through to vanilla default styling".
 *
 * <p>Read-modify-write: existing keys we don't have an edit for are left
 * alone. Setting a value to {@code null} (the "no color" sentinel) removes
 * the key from the file so the file stays diff-clean and the loader falls
 * through.
 *
 * <p>Tmp-file + atomic-move semantics match {@link PackChanceWriter} so a
 * crash mid-write cannot truncate the file.
 */
public final class PackColorWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackColorWriter() {}

    /**
     * Apply color edits to {@code packId}'s {@code colors/defaults.json}
     * file. Returns true on success (including no-op); false when the
     * path can't be resolved or write fails.
     */
    public static boolean writeColors(String packId, Map<ChanceKind, ChatFormatting> edits) {
        if (edits == null || edits.isEmpty()) return true;
        Path file = PackPaths.colorFile(packId, "defaults");
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve color file path for pack '{}'", packId);
            return false;
        }
        JsonObject root = readRootOrEmpty(file);
        boolean changed = false;
        for (Map.Entry<ChanceKind, ChatFormatting> entry : edits.entrySet()) {
            ChanceKind kind = entry.getKey();
            ChatFormatting color = entry.getValue();
            if (kind == null) continue;
            String key = kind.key();
            if (color == null) {
                // Null marks a clear → drop the key so the loader falls
                // through to vanilla default styling.
                if (root.has(key)) {
                    root.remove(key);
                    changed = true;
                }
                continue;
            }
            if (!Colors.isColor(color)) {
                LOGGER.warn("[AdventureItemNames] refusing to write non-color formatting '{}' for key '{}'",
                    color.getName(), key);
                continue;
            }
            String name = color.getName();
            JsonElement existing = root.get(key);
            if (existing != null && existing.isJsonPrimitive() && existing.getAsJsonPrimitive().isString()
                && name.equals(existing.getAsString())) {
                continue;
            }
            root.add(key, new JsonPrimitive(name));
            changed = true;
        }
        if (!changed) return true;

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote color file for pack '{}' ({})", packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write color file at '{}': {}", file, ex.getMessage());
            return false;
        }
    }

    private static JsonObject readRootOrEmpty(Path file) {
        if (!Files.exists(file)) return new JsonObject();
        try (InputStream in = Files.newInputStream(file)) {
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] existing color file '{}' unreadable, recreating: {}", file, ex.getMessage());
            return new JsonObject();
        }
    }
}
