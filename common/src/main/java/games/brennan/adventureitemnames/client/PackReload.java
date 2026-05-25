package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.repository.PackRepository;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Helper that picks up a freshly-written world datapack and rolls it into
 * the running server's selected pack set without requiring the user to
 * leave and reload the world.
 *
 * <p>Three-step dance on the server thread:
 * <ol>
 *   <li>{@code PackRepository.reload()} — rescans the world's {@code datapacks/}
 *       folder so the new pack becomes discoverable.</li>
 *   <li>{@code setSelected(existing + newPackId)} — activates the new
 *       pack alongside whatever was already loaded.</li>
 *   <li>{@code MinecraftServer.reloadResources(...)} — re-runs every
 *       reload listener (including the mod's pool/chain listeners in
 *       {@code NameRegistry}) so {@code PackGrouping.snapshot()} sees the
 *       new pack's pools.</li>
 * </ol>
 *
 * <p>{@code onComplete} runs on the client thread after the async reload
 * resolves — typically swaps the active screen to the new pack's
 * {@code PoolListScreen}.
 */
@Environment(EnvType.CLIENT)
public final class PackReload {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PackReload() {}

    /**
     * Enable {@code newPackId} on the integrated server and trigger a data
     * reload. No-ops with a log warning when no singleplayer server is
     * running.
     *
     * @param newPackId      pack id to add to the selected set (e.g. {@code "file/my_pack"})
     * @param onComplete     runs on the client thread once the reload finishes; may be {@code null}
     */
    public static void enableAndReload(String newPackId, Runnable onComplete) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[AdventureItemNames] no integrated server — cannot enable pack '{}'", newPackId);
            return;
        }
        server.execute(() -> {
            PackRepository repo = server.getPackRepository();
            repo.reload();

            Set<String> selected = new LinkedHashSet<>(repo.getSelectedIds());
            selected.add(newPackId);
            repo.setSelected(selected);

            server.reloadResources(repo.getSelectedIds())
                .thenAcceptAsync(v -> {
                    LOGGER.info("[AdventureItemNames] enabled and reloaded new pack '{}'", newPackId);
                    if (onComplete != null) onComplete.run();
                }, Minecraft.getInstance())
                .exceptionally(ex -> {
                    LOGGER.warn("[AdventureItemNames] pack reload failed for '{}': {}", newPackId, ex.getMessage());
                    return null;
                });
        });
    }

    /**
     * Remove {@code packId} from the integrated server's selected pack set
     * and trigger a data reload — counterpart to {@link #enableAndReload}
     * used after a pack folder has been deleted from disk. No-ops with a
     * log warning when no singleplayer server is running.
     *
     * @param packId      pack id to drop from the selected set
     * @param onComplete  runs on the client thread once the reload finishes; may be {@code null}
     */
    public static void disableAndReload(String packId, Runnable onComplete) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[AdventureItemNames] no integrated server — cannot disable pack '{}'", packId);
            return;
        }
        server.execute(() -> {
            PackRepository repo = server.getPackRepository();
            repo.reload();

            Set<String> selected = new LinkedHashSet<>(repo.getSelectedIds());
            selected.remove(packId);
            repo.setSelected(selected);

            server.reloadResources(repo.getSelectedIds())
                .thenAcceptAsync(v -> {
                    LOGGER.info("[AdventureItemNames] disabled and reloaded after dropping pack '{}'", packId);
                    if (onComplete != null) onComplete.run();
                }, Minecraft.getInstance())
                .exceptionally(ex -> {
                    LOGGER.warn("[AdventureItemNames] pack reload failed after dropping '{}': {}", packId, ex.getMessage());
                    return null;
                });
        });
    }

    /**
     * Trigger an integrated-server data reload without changing the
     * selected pack set — used after a single pool/chain file inside an
     * already-loaded pack has been added or removed on disk. Mirrors
     * {@code ConfigSave.triggerDataReload} so the in-game registries pick
     * up the change without requiring {@code /reload}.
     */
    public static void reloadInPlace(Runnable onComplete) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[AdventureItemNames] no integrated server — data won't reload until next /reload or world load");
            return;
        }
        server.execute(() ->
            server.reloadResources(server.getPackRepository().getSelectedIds())
                .thenAcceptAsync(v -> {
                    if (onComplete != null) onComplete.run();
                }, Minecraft.getInstance())
                .exceptionally(ex -> {
                    LOGGER.warn("[AdventureItemNames] in-place data reload failed: {}", ex.getMessage());
                    return null;
                }));
    }
}
