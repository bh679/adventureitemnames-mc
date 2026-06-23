package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Recursively deletes a whole datapack folder from the in-game UI.
 * Counterpart to {@link PackCreator}.
 *
 * <p>Two destination cases mirror {@link PoolCreator}:
 * <ul>
 *   <li><b>User-created world pack</b> (pack id {@code file/&lt;slug&gt;}) —
 *       removes {@code &lt;world&gt;/datapacks/&lt;slug&gt;/}. Works in any
 *       singleplayer session.</li>
 *   <li><b>Shipped built-in pack</b> (e.g. {@code mod/adventureitemnames/wholesome}) —
 *       removes the source-tree pack root resolved via
 *       {@link PackPaths#dataRootFor}. Only works in a Loom dev environment.</li>
 * </ul>
 *
 * <p>Walks the tree bottom-up via {@link Files#walkFileTree} so files are
 * deleted before their containing directories. No external libraries.
 */
public final class PackDeleter {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record DeleteResult(boolean ok, Path deletedFrom, String error) {
        public static DeleteResult fail(String why) {
            return new DeleteResult(false, null, why);
        }
    }

    private PackDeleter() {}

    /**
     * Delete the pack identified by {@code packId} from disk.
     *
     * @param packId pack id as reported by {@code Resource.sourcePackId()} or
     *               {@code "file/<slug>"} for a user-created world pack
     * @return ok=true with the deleted path on success
     */
    public static DeleteResult delete(String packId) {
        if (packId == null || packId.isEmpty()) {
            return DeleteResult.fail("pack id is empty");
        }

        Path packRoot = resolvePackRoot(packId);
        if (packRoot == null) {
            return DeleteResult.fail("cannot resolve disk path for pack '" + packId
                + "' — built-in packs are read-only outside a Loom dev environment");
        }
        if (!Files.exists(packRoot)) {
            return DeleteResult.fail("pack folder '" + packRoot + "' does not exist");
        }

        try {
            deleteRecursive(packRoot);
            LOGGER.info("[AdventureItemNames] deleted pack '{}' from {}", packId, packRoot);
            return new DeleteResult(true, packRoot, null);
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to delete pack '{}' at {}: {}",
                packId, packRoot, ex.getMessage());
            return DeleteResult.fail("delete failed: " + ex.getMessage());
        }
    }

    /**
     * True when {@link #delete} could succeed for this pack id right now —
     * used by the UI to gate the per-row ✕ button.
     */
    public static boolean canDelete(String packId) {
        Path root = resolvePackRoot(packId);
        return root != null && Files.exists(root);
    }

    /**
     * Resolve the on-disk pack root folder. User-created world packs map to
     * {@code <world>/datapacks/<slug>/}; built-in packs map to the source-tree
     * pack root (which is the {@code data/adventureitemnames} parent's parent
     * — back up two levels from {@link PackPaths#dataRootFor}). Returns null
     * when the path can't be resolved.
     */
    private static Path resolvePackRoot(String packId) {
        if (packId == null) return null;
        if (packId.startsWith("file/")) {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) return null;
            String slug = packId.substring("file/".length());
            if (slug.isEmpty()) return null;
            return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(slug);
        }
        String canonical = PackPaths.canonicalize(packId);
        Path data = PackPaths.dataRootFor(canonical);
        if (data == null) return null;
        // data points to <packRoot>/data/adventureitemnames; back up two levels.
        Path nsParent = data.getParent();
        if (nsParent == null) return null;
        Path packRoot = nsParent.getParent();
        if (packRoot == null) return null;
        // Sanity: for the base mod, dataRootFor returns common/src/main/resources/data/adventureitemnames.
        // Backing up two levels lands on common/src/main/resources/, which is NOT a pack root we want to nuke.
        // Refuse to delete anything that isn't a sibling resourcepacks/<slug> folder or under it.
        if (!packRoot.toString().contains("resourcepacks")) {
            LOGGER.warn("[AdventureItemNames] refusing to delete pack '{}' — resolved root {} is not under resourcepacks/", packId, packRoot);
            return null;
        }
        return packRoot;
    }

    private static void deleteRecursive(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
