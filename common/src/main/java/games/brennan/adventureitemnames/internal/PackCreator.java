package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Scaffolds a brand-new datapack from the in-game UI. Always writes to
 * the current singleplayer world's {@code datapacks/&lt;slug&gt;/} so the
 * pack is immediately loadable via the existing pack-repository reload
 * path. In a Loom dev environment, mirrors the same files to
 * {@code common/src/main/resources/resourcepacks/&lt;slug&gt;/} so the
 * contributor can commit the pack to source control.
 *
 * <p>Pack content scaffolded: {@code pack.mcmeta}, a single
 * {@code naming/pools/&lt;slug&gt;_starter.json} placeholder pool (so the
 * pack is visible in {@link games.brennan.adventureitemnames.client.PackGrouping}'s
 * snapshot, which only counts packs that contribute pools), and an empty
 * {@code naming/chains/} directory.
 *
 * <p>Atomic write semantics mirror {@link PackPoolWriter}: serialise to a
 * {@code .tmp} sibling first, then {@link StandardCopyOption#ATOMIC_MOVE}
 * into place so a crash mid-write cannot leave a half-written pack.mcmeta.
 */
public final class PackCreator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "adventureitemnames";
    /** Minecraft 1.21.1 datapack format. Matches the value used by the shipped themed packs. */
    private static final int PACK_FORMAT = 48;

    public record CreateResult(boolean ok, String packId, Path worldRoot, Path srcRoot, String error) {
        public static CreateResult fail(String why) {
            return new CreateResult(false, null, null, null, why);
        }
    }

    private PackCreator() {}

    /**
     * Create a new datapack on disk.
     *
     * @param slug         folder name (already slugified — lowercase, underscores)
     * @param description  goes into {@code pack.mcmeta}'s description field
     * @return result with {@code packId} {@code "file/<slug>"} on success
     */
    public static CreateResult create(String slug, String description) {
        if (slug == null || slug.isEmpty()) {
            return CreateResult.fail("slug is empty");
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return CreateResult.fail("no singleplayer server — open a world first");
        }

        Path worldDatapacks = server.getWorldPath(LevelResource.DATAPACK_DIR);
        Path worldPackRoot = worldDatapacks.resolve(slug);
        if (Files.exists(worldPackRoot)) {
            return CreateResult.fail("pack folder '" + slug + "' already exists in world datapacks");
        }

        Path srcPackRoot = PackPaths.srcTreePackRoot(slug);
        if (srcPackRoot != null && Files.exists(srcPackRoot)) {
            return CreateResult.fail("pack folder '" + slug + "' already exists in source tree");
        }

        try {
            writePackTree(worldPackRoot, slug, description);
            LOGGER.info("[AdventureItemNames] created pack '{}' at {}", slug, worldPackRoot);
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write pack '{}' to world: {}", slug, ex.getMessage());
            return CreateResult.fail("write failed: " + ex.getMessage());
        }

        if (srcPackRoot != null) {
            try {
                writePackTree(srcPackRoot, slug, description);
                LOGGER.info("[AdventureItemNames] mirrored pack '{}' to source tree at {}", slug, srcPackRoot);
            } catch (IOException ex) {
                LOGGER.warn("[AdventureItemNames] failed to mirror pack '{}' to source tree: {}", slug, ex.getMessage());
            }
        }

        return new CreateResult(true, "file/" + slug, worldPackRoot, srcPackRoot, null);
    }

    private static void writePackTree(Path packRoot, String slug, String description) throws IOException {
        Path dataDir = packRoot.resolve("data").resolve(NAMESPACE).resolve("naming");
        Path poolsDir = dataDir.resolve("pools");
        Path chainsDir = dataDir.resolve("chains");
        Files.createDirectories(poolsDir);
        Files.createDirectories(chainsDir);

        atomicWriteJson(packRoot.resolve("pack.mcmeta"), buildPackMcmeta(description));

        String poolFileName = slug + "_starter.json";
        atomicWriteJson(poolsDir.resolve(poolFileName), buildStarterPool(slug));
    }

    private static JsonObject buildPackMcmeta(String description) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description == null || description.isEmpty()
            ? "User-created Adventure Item Names pack"
            : description);
        pack.addProperty("pack_format", PACK_FORMAT);

        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    private static JsonObject buildStarterPool(String slug) {
        JsonObject root = new JsonObject();
        root.addProperty("id", NAMESPACE + ":" + slug + "_starter");

        JsonObject entry = new JsonObject();
        entry.addProperty("text", "Placeholder — edit me");

        JsonArray entries = new JsonArray();
        entries.add(entry);
        root.add("entries", entries);
        return root;
    }

    private static void atomicWriteJson(Path file, JsonObject body) throws IOException {
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(body);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
