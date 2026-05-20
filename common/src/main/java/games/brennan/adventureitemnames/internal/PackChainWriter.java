package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a {@link NameChain} to a specific pack's
 * {@code naming/chains/<path>.json} on disk in a Loom dev environment.
 * Only meaningful when {@link PackPaths#projectRootAvailable()} returns
 * true — production users have the pack files inside the mod jar and
 * cannot write to them.
 *
 * <p>Atomic write semantics: serialize to a {@code .tmp} sibling first,
 * then {@link StandardCopyOption#ATOMIC_MOVE} into place so a crash
 * mid-write cannot produce a truncated chain file.
 */
public final class PackChainWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackChainWriter() {}

    /**
     * Write {@code chain} to the pack identified by {@code packId}.
     *
     * @param packId  resource-pack id as reported by
     *                {@code Resource.sourcePackId()}.
     * @param chain   the chain record to serialize. Its {@link NameChain#id()}
     *                determines the file path inside the pack.
     * @param replace whether the file should claim the {@code "replace": true}
     *                semantics (overrides lower-priority layers) or
     *                {@code "replace": false} (additive — themed packs).
     * @return true on success; false when the pack is unknown, the
     *         project root is unavailable, or the write fails. Errors
     *         are logged.
     */
    public static boolean writeChain(String packId, NameChain chain, boolean replace) {
        if (chain == null) return false;
        Path file = PackPaths.chainFile(packId, chain.id().getPath());
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve disk path for pack '{}' — chain {} not written",
                packId, chain.id());
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = NameCodec.writeChain(chain, replace);
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote chain {} to pack '{}' ({})", chain.id(), packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write chain {} to pack '{}': {}",
                chain.id(), packId, ex.getMessage());
            return false;
        }
    }

    /**
     * Delete the pack's chain file (used when a pack's contribution to a
     * chain has been emptied out by the user). Silent on missing files.
     * Returns false if the path can't be resolved or deletion fails.
     */
    public static boolean deleteChain(String packId, String chainPath) {
        Path file = PackPaths.chainFile(packId, chainPath);
        if (file == null) return false;
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to delete chain file at '{}': {}", file, ex.getMessage());
            return false;
        }
    }
}
