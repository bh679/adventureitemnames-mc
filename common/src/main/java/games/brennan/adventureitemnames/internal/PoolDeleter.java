package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deletes a single pool JSON file from a pack. Counterpart to
 * {@link PoolCreator}. Destination resolution mirrors that class's
 * {@link PoolCreator#create} flow:
 * <ul>
 *   <li><b>User-created world pack</b> (pack id {@code file/&lt;slug&gt;}) —
 *       removes {@code &lt;world&gt;/datapacks/&lt;slug&gt;/data/adventureitemnames/naming/pools/&lt;poolPath&gt;.json}.</li>
 *   <li><b>Shipped built-in pack</b> — removes the source-tree pool file via
 *       {@link PackPaths#poolFile}. Only works in a Loom dev environment.</li>
 * </ul>
 *
 * <p>Silent on missing files (matches the {@link PackChainWriter#deleteChain}
 * pattern). Returns false when the path cannot be resolved or deletion throws.
 */
public final class PoolDeleter {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "adventureitemnames";

    private PoolDeleter() {}

    /**
     * Delete the pool file for {@code poolPath} from the pack identified by
     * {@code packId}.
     *
     * @param packId   target pack
     * @param poolPath pool {@link net.minecraft.resources.ResourceLocation#getPath()} —
     *                 i.e. the part after the namespace
     * @return true on success (or if the file was already missing)
     */
    public static boolean deletePool(String packId, String poolPath) {
        Path file = resolvePoolFile(packId, poolPath);
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve disk path for pool '{}' in pack '{}'",
                poolPath, packId);
            return false;
        }
        try {
            boolean existed = Files.deleteIfExists(file);
            if (existed) {
                LOGGER.info("[AdventureItemNames] deleted pool '{}' from pack '{}' ({})",
                    poolPath, packId, file);
            }
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to delete pool file at '{}': {}",
                file, ex.getMessage());
            return false;
        }
    }

    /**
     * True when {@link #deletePool} could resolve a writable path for this
     * pack id right now — used by the UI to gate the per-row ✕ button.
     */
    public static boolean canDelete(String packId) {
        if (packId == null) return false;
        if (packId.startsWith("file/")) {
            return Minecraft.getInstance().getSingleplayerServer() != null;
        }
        // Use a non-empty placeholder so dataRootFor's null-check works.
        return PackPaths.poolFile(PackPaths.canonicalize(packId), "probe") != null;
    }

    private static Path resolvePoolFile(String packId, String poolPath) {
        if (packId == null || poolPath == null) return null;
        if (packId.startsWith("file/")) {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) return null;
            String packSlug = packId.substring("file/".length());
            return server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve(packSlug)
                .resolve("data").resolve(NAMESPACE).resolve("naming").resolve("pools")
                .resolve(poolPath + ".json");
        }
        return PackPaths.poolFile(PackPaths.canonicalize(packId), poolPath);
    }
}
