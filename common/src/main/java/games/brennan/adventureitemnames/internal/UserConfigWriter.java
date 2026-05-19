package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
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
import java.util.Map;
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
     * removing entries from {@code pools[]}. Weight overrides go into the
     * {@code weight_overrides} map; passing a negative value removes the
     * override for that key.
     *
     * @return true on success; false if the config dir is unset or write
     *     failed. The error is logged.
     */
    public static synchronized boolean save(Set<ResourceLocation> disabledPools,
                                            Set<ResourceLocation> enabledPools,
                                            Map<String, Float> weightOverrides) {
        Path configDir = ConfigPaths.get();
        if (configDir == null) {
            LOGGER.warn("[AdventureItemNames] config dir not set — cannot save user config");
            return false;
        }
        Path file = configDir.resolve(FILE_NAME);
        JsonObject root = readRootOrEmpty(file);

        applyDisableEdits(root, disabledPools, enabledPools);
        applyWeightEdits(root, weightOverrides);

        try {
            Files.createDirectories(configDir);
            Path tmp = configDir.resolve(FILE_NAME + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().create().toJson(root);
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

    private static void applyDisableEdits(JsonObject root, Set<ResourceLocation> disable, Set<ResourceLocation> enable) {
        if ((disable == null || disable.isEmpty()) && (enable == null || enable.isEmpty())) return;
        JsonArray pools;
        JsonElement existing = root.get("pools");
        if (existing != null && existing.isJsonArray()) {
            pools = existing.getAsJsonArray();
        } else {
            pools = new JsonArray();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement el : pools) {
            if (el.isJsonPrimitive()) ids.add(el.getAsString());
        }
        if (disable != null) for (ResourceLocation rl : disable) ids.add(rl.toString());
        if (enable != null) for (ResourceLocation rl : enable) ids.remove(rl.toString());

        JsonArray rebuilt = new JsonArray();
        for (String s : ids) rebuilt.add(s);
        root.add("pools", rebuilt);
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
                sorted.put(key, new com.google.gson.JsonPrimitive(v));
            }
        }
        JsonObject rebuilt = new JsonObject();
        for (var e : sorted.entrySet()) rebuilt.add(e.getKey(), e.getValue());
        root.add("weight_overrides", rebuilt);
    }
}
