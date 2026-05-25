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
}
