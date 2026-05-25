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
        JsonObject root = new JsonObject();
        root.add("id", new JsonPrimitive(selector.id().toString()));
        root.add("applies_to", new JsonPrimitive(selector.appliesTo().toString()));
        JsonObject tiers = new JsonObject();
        for (Map.Entry<String, ResourceLocation> e : selector.tiers().entrySet()) {
            if (e.getValue() == null) continue; // (none) sentinel → omit key
            tiers.add(e.getKey(), new JsonPrimitive(e.getValue().toString()));
        }
        root.add("tiers", tiers);

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
        JsonElement tiersEl = root.get("tiers");
        JsonObject tiers = (tiersEl != null && tiersEl.isJsonObject())
            ? tiersEl.getAsJsonObject() : new JsonObject();
        for (Map.Entry<String, Optional<ResourceLocation>> e : tierEdits.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                tiers.remove(e.getKey());
            } else {
                tiers.add(e.getKey(), new JsonPrimitive(e.getValue().get().toString()));
            }
        }
        root.add("tiers", tiers);

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
