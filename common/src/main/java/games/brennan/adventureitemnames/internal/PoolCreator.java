package games.brennan.adventureitemnames.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Scaffolds a brand-new pool inside an existing pack from the in-game UI.
 * Destination resolution mirrors {@link PackCreator}'s pack-create flow
 * but operates on a single pool file rather than a whole pack tree.
 *
 * <p>Two destination cases:
 * <ul>
 *   <li><b>User-created world pack</b> (pack id {@code file/&lt;slug&gt;}) —
 *       writes to {@code &lt;world&gt;/datapacks/&lt;slug&gt;/data/adventureitemnames/naming/pools/&lt;poolSlug&gt;.json}.
 *       Works in any singleplayer session.</li>
 *   <li><b>Shipped built-in pack</b> (e.g. {@code mod/adventureitemnames/wholesome}) —
 *       writes to the source tree via {@link PackPaths#poolFile}. Only
 *       works in a Loom dev environment ({@code projectRootAvailable()} true).</li>
 * </ul>
 *
 * <p>Returns {@link CreateResult#fail} when the destination cannot be
 * resolved (production user editing a built-in pack), when the pool file
 * already exists, or when the write itself fails.
 */
public final class PoolCreator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "adventureitemnames";

    public record CreateResult(boolean ok, String poolId, Path writtenTo, String error) {
        public static CreateResult fail(String why) {
            return new CreateResult(false, null, null, why);
        }
    }

    private PoolCreator() {}

    /**
     * Write a pool with one placeholder entry to the given pack.
     *
     * @param packId    target pack (e.g. {@code "file/my_pack"} or {@code "mod/adventureitemnames/wholesome"})
     * @param poolSlug  pool file name and ResourceLocation path (already slugified)
     * @param initialEntry  first entry text (placeholder is used when null/empty)
     */
    public static CreateResult create(String packId, String poolSlug, String initialEntry) {
        if (poolSlug == null || poolSlug.isEmpty()) {
            return CreateResult.fail("pool name is empty");
        }
        if (packId == null || packId.isEmpty()) {
            return CreateResult.fail("pack id is empty");
        }

        Path file = resolvePoolFile(packId, poolSlug);
        if (file == null) {
            return CreateResult.fail("cannot write to this pack — open it in a Loom dev environment, or pick a user-created pack");
        }
        if (Files.exists(file)) {
            return CreateResult.fail("pool '" + poolSlug + "' already exists in this pack");
        }

        String entryText = (initialEntry == null || initialEntry.trim().isEmpty())
            ? "Placeholder — edit me"
            : initialEntry.trim();

        try {
            Files.createDirectories(file.getParent());
            PackCreator.atomicWriteJson(file, buildPool(poolSlug, entryText));
            // Register the pool in the in-memory registry under the source
            // pack id the caller chose. The subsequent reload triggered by
            // CreatePoolPopup can't see the file in Loom dev mode (classpath
            // already cached at launch), so without this the pool would be
            // missing from PackGrouping.snapshot() and the post-create
            // PoolListScreen would render without it. Pass packId raw — the
            // listener-side POOL_PACKS uses raw source pack ids (e.g.
            // "fabric" on Fabric), and PoolListScreen.pack().packId() is the
            // same raw id, so the grouping must match.
            ResourceLocation poolId = ResourceLocation.fromNamespaceAndPath(NAMESPACE, poolSlug);
            NamePool pool = new NamePool(poolId, List.of(NamePool.PoolEntry.universal(entryText)));
            NameRegistry.putPoolInMemory(pool, packId);
            LOGGER.info("[AdventureItemNames] created pool '{}' in pack '{}' at {}", poolSlug, packId, file);
            return new CreateResult(true, NAMESPACE + ":" + poolSlug, file, null);
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write pool '{}' to pack '{}': {}",
                poolSlug, packId, ex.getMessage());
            return CreateResult.fail("write failed: " + ex.getMessage());
        }
    }

    /**
     * Resolve the on-disk pool file path. User-created world packs go to
     * the singleplayer server's datapacks dir; built-in packs go through
     * {@link PackPaths#poolFile} (dev-mode source tree). Returns {@code null}
     * when neither path is reachable.
     */
    private static Path resolvePoolFile(String packId, String poolSlug) {
        if (packId.startsWith("file/")) {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) return null;
            String packSlug = packId.substring("file/".length());
            return server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve(packSlug)
                .resolve("data").resolve(NAMESPACE).resolve("naming").resolve("pools")
                .resolve(poolSlug + ".json");
        }
        return PackPaths.poolFile(PackPaths.canonicalize(packId), poolSlug);
    }

    /**
     * True when {@link #create} could succeed for this pack id right now —
     * used by the UI to enable/disable the {@code + New pool} button.
     */
    public static boolean canWriteTo(String packId) {
        if (packId == null) return false;
        if (packId.startsWith("file/")) {
            return Minecraft.getInstance().getSingleplayerServer() != null;
        }
        return PackPaths.poolFile(PackPaths.canonicalize(packId), "probe") != null;
    }

    private static JsonObject buildPool(String poolSlug, String entryText) {
        JsonObject root = new JsonObject();
        root.addProperty("id", NAMESPACE + ":" + poolSlug);

        JsonObject entry = new JsonObject();
        entry.addProperty("text", entryText);

        JsonArray entries = new JsonArray();
        entries.add(entry);
        root.add("entries", entries);
        return root;
    }
}
