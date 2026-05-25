package games.brennan.adventureitemnames.internal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamePool;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a {@link NamePool} to a specific pack's
 * {@code naming/pools/<path>.json} on disk in a Loom dev environment.
 * Only meaningful when {@link PackPaths#projectRootAvailable()} returns
 * true — production users have the pack files inside the mod jar and
 * cannot write to them.
 *
 * <p>Sibling of {@link PackChainWriter}. Pools differ from chains in
 * that each pool is sourced from a single file in a single winning
 * pack (no cross-pack merging via {@code "replace": false}), so the
 * writer takes a fully-materialised pool and dumps it verbatim — no
 * {@link PerPackSplitter} equivalent needed.
 *
 * <p>Atomic write semantics: serialise to a {@code .tmp} sibling first,
 * then {@link StandardCopyOption#ATOMIC_MOVE} into place so a crash
 * mid-write cannot produce a truncated pool file.
 */
public final class PackPoolWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackPoolWriter() {}

    /**
     * Write {@code pool} to the pack identified by {@code packId}.
     *
     * @param packId resource-pack id as reported by
     *               {@code Resource.sourcePackId()} (canonicalise via
     *               {@link PackPaths#canonicalize} before calling so the
     *               Loom {@code generated_<hash>} alias collapses to the
     *               base mod).
     * @param pool   the pool record to serialise. Its {@link NamePool#id()}
     *               determines the file path inside the pack.
     * @return true on success; false when the pack is unknown, the
     *         project root is unavailable, or the write fails. Errors
     *         are logged.
     */
    public static boolean writePool(String packId, NamePool pool) {
        if (pool == null) return false;
        Path file = PackPaths.poolFile(packId, pool.id().getPath());
        if (file == null) {
            LOGGER.warn("[AdventureItemNames] cannot resolve disk path for pack '{}' — pool {} not written",
                packId, pool.id());
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = NameCodec.writePool(pool);
            String body = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[AdventureItemNames] wrote pool {} to pack '{}' ({})", pool.id(), packId, file);
            return true;
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to write pool {} to pack '{}': {}",
                pool.id(), packId, ex.getMessage());
            return false;
        }
    }
}
