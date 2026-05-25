package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import java.util.Set;
import java.util.TreeSet;

/**
 * Merges UI-driven disable/enable edits into a pack's
 * {@code data/<ns>/disabled/<file>.json} on disk in a Loom dev environment.
 * Only meaningful when {@link PackPaths#projectRootAvailable()} returns
 * true — production users have the pack files inside the mod jar and
 * cannot write to them.
 *
 * <p>Read-modify-write: any fields we don't manage from the UI
 * ({@code _comment}, {@code items}, {@code mobs}) are preserved in place.
 * The {@code pools}, {@code chains}, and {@code selectors} arrays are
 * rewritten from the merged disable set: lower-priority "enabled" ids are
 * dropped from each array, "disabled" ids are added (de-duped).
 *
 * <p>Sibling of {@link PackChainWriter} / {@link PackPoolWriter}. Same
 * tmp-file + atomic-move semantics so a crash mid-write cannot truncate
 * the file.
 */
public final class PackDisableWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackDisableWriter() {}

    /**
     * Apply disable/enable edits to {@code packId}'s {@code defaults.json}
     * disable file. Returns true on success (including no-op when nothing
     * changed); false when the path can't be resolved or write fails.
     *
     * @param disabledPools     pool ids the user marked as disabled this session
     * @param enabledPools      pool ids the user toggled back on (removed from the array)
     * @param disabledChains    chain ids the user marked as disabled
     * @param enabledChains     chain ids the user toggled back on
     * @param disabledSelectors selector ids marked disabled
     * @param enabledSelectors  selector ids toggled back on
     */
    public static boolean writeDisables(String packId,
                                        Set<ResourceLocation> disabledPools,
                                        Set<ResourceLocation> enabledPools,
                                        Set<ResourceLocation> disabledChains,
                                        Set<ResourceLocation> enabledChains,
                                        Set<ResourceLocation> disabledSelectors,
                                        Set<ResourceLocation> enabledSelectors) {
        Path file = PackPaths.disableFile(packId, "defaults");
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve disable file path for pack '{}'", packId);
            return false;
        }
        JsonObject root = readRootOrDefault(file);
        boolean changed = false;
        changed |= mergeIdList(root, "pools",     disabledPools,     enabledPools);
        changed |= mergeIdList(root, "chains",    disabledChains,    enabledChains);
        changed |= mergeIdList(root, "selectors", disabledSelectors, enabledSelectors);
        if (!changed) return true;

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root);
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote disable layer for pack '{}' ({})", packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write disable file at '{}': {}", file, ex.getMessage());
            return false;
        }
    }

    /**
     * Merge {@code added} into and {@code removed} out of the named array
     * inside {@code root}. Preserves any pre-existing entries the UI
     * doesn't manage (e.g. wildcards, ids set by hand). Returns true when
     * the array's contents actually changed.
     */
    private static boolean mergeIdList(JsonObject root, String key,
                                       Set<ResourceLocation> added, Set<ResourceLocation> removed) {
        Set<String> current = new LinkedHashSet<>();
        JsonElement el = root.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    current.add(item.getAsString());
                }
            }
        }
        boolean changed = false;
        if (removed != null) {
            for (ResourceLocation id : removed) {
                if (current.remove(id.toString())) changed = true;
            }
        }
        if (added != null) {
            for (ResourceLocation id : added) {
                if (current.add(id.toString())) changed = true;
            }
        }
        if (!changed && el != null && el.isJsonArray()) return false;
        // Sort the array for stable diffs in git.
        Set<String> sorted = new TreeSet<>(current);
        JsonArray arr = new JsonArray();
        for (String s : sorted) arr.add(new JsonPrimitive(s));
        root.add(key, arr);
        return changed || el == null;
    }

    /**
     * Read the existing disable file, or return a fresh empty object with
     * the standard schema seeded so write-time diffs stay readable. The
     * seeded {@code _comment} keeps the file's purpose discoverable when
     * the writer creates it from scratch.
     */
    private static JsonObject readRootOrDefault(Path file) {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                JsonElement parsed = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (Exception ex) {
                LOGGER.warn("[AdventureItemNames] existing disable file '{}' unreadable, recreating: {}", file, ex.getMessage());
            }
        }
        JsonObject root = new JsonObject();
        root.add("_comment", new JsonPrimitive(
            "UI-managed disable list — edits in the in-game config UI are written here."));
        return root;
    }
}
