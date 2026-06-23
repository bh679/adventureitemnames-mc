package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
//? if >=1.21.1 {
import net.minecraft.server.packs.PackLocationInfo;
//?}
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Drives the {@link NameRegistry} reload listeners against the mod's
 * bundled data packs at client-init time, so the in-game config screen
 * has content to render even when no world is loaded.
 *
 * <p>The normal datapack reload path runs only when an integrated server
 * boots (singleplayer world load) or a dedicated server starts — both
 * out of reach from the title-screen Mods → Config button. Without this
 * preload, the title-screen-launched config screen sees an empty registry
 * and renders blank Chains / Datapacks lists.
 *
 * <p>This preload only walks <em>shipped</em> packs (mc_names, wholesome,
 * discord, atla, adventuretime). When the player subsequently loads a
 * world, the loader-registered {@code AddReloadListenerEvent} hooks fire
 * and re-populate the registry from the world's full datapack set
 * (shipped + user datapacks), fully replacing this preview. The dev
 * chain-overlay and user-selector overlays survive both passes by design.
 *
 * <p>This class is loader-neutral. Each loader's client entrypoint calls
 * {@link #loadIntoRegistry()} once during client init, gated to
 * client-only so dedicated servers don't pay the cost. Saving from the
 * title screen still works because {@link UserConfigLoader#reload()} runs
 * before this preload and the user-config file is persisted independently
 * of any world's datapack dir.
 */
public final class BundledPackLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sentinel pack id meaning "the base mod's own {@code data/} tree" —
     * resolved against {@code /pack.mcmeta} at the classpath root instead
     * of {@code /resourcepacks/<id>/pack.mcmeta}. The base mod ships the
     * foundational selectors, chains, and pools at {@code data/} root;
     * the resourcepacks layer themed overrides on top. Without this entry
     * the preload sees 0 selectors and only the resourcepack subset of
     * chains/pools — same blank-screen symptom the preload is meant to fix.
     */
    private static final String BASE_MOD_PACK = "__base__";

    /**
     * Pack ids in load order — base mod first (foundation), then each
     * shipped resourcepack overlay. Order matters for the chain
     * merge-by-layer behaviour in {@link NameRegistry.ChainListener}:
     * earlier packs are lower-priority, later packs are higher-priority.
     * Kept in sync by hand with the per-loader {@code registerBuiltinPack}
     * calls — if a new shipped pack is added it must be appended here too,
     * or it won't appear in the title-screen preview (it will still appear
     * in-world via the normal {@code AddPackFindersEvent} path).
     */
    private static final List<String> BUNDLED_PACK_IDS = List.of(
        BASE_MOD_PACK,
        "mc_names", "wholesome", "discord", "atla", "adventuretime", "rickandmorty"
    );

    private BundledPackLoader() {}

    /**
     * Resolve every bundled pack via classpath URL lookup, wrap them in a
     * sync {@link MultiPackResourceManager}, and drive each
     * {@link NameRegistry} reload listener against it. Idempotent —
     * subsequent calls overwrite the in-memory maps with the same data.
     */
    public static synchronized void loadIntoRegistry() {
        List<PackResources> packs = new ArrayList<>();
        for (String packId : BUNDLED_PACK_IDS) {
            Path packRoot = resolveBundledPackPath(packId);
            if (packRoot == null) {
                LOGGER.warn("[AdventureItemNames] bundled pack '{}' not found on classpath — skipping client-init preload", packId);
                continue;
            }
            String displayId = BASE_MOD_PACK.equals(packId) ? "base" : packId;
            // 1.21 wraps pack identity in a PackLocationInfo; 1.20.1's PathPackResources
            // takes (packId, root, isBuiltin) directly.
            //? if >=1.21.1 {
            PackLocationInfo info = new PackLocationInfo(
                "bundled/adventureitemnames/" + displayId,
                Component.literal("Adventure Item Names — " + displayId),
                PackSource.BUILT_IN,
                Optional.empty()
            );
            packs.add(new PathPackResources(info, packRoot));
            //?} else {
            /*packs.add(new PathPackResources(
                "bundled/adventureitemnames/" + displayId, packRoot, true));
            *///?}
        }
        if (packs.isEmpty()) {
            LOGGER.warn("[AdventureItemNames] no bundled packs resolved — title-screen config preview will be empty");
            return;
        }
        try (MultiPackResourceManager mgr = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
            driveListener(NameRegistry.poolListener(), mgr);
            driveListener(NameRegistry.chainListener(), mgr);
            driveListener(NameRegistry.selectorListener(), mgr);
            driveListener(NameRegistry.configListener(), mgr);
            driveListener(NameRegistry.chanceListener(), mgr);
            LOGGER.info("[AdventureItemNames] client-init preload complete — {} bundled pack(s)", packs.size());
        }
    }

    /**
     * Synchronously drive {@code listener.reload(...)} against {@code mgr}.
     * Uses a pass-through {@link PreparableReloadListener.PreparationBarrier}
     * (no cross-listener ordering guarantees needed at this stage) and a
     * caller-thread {@link Executor} so prepare and apply run inline. The
     * returned future is joined; exceptions are logged and swallowed so a
     * single bad pack doesn't tank the rest of the preload.
     */
    private static void driveListener(PreparableReloadListener listener,
                                      MultiPackResourceManager mgr) {
        PreparableReloadListener.PreparationBarrier passthrough = new PreparableReloadListener.PreparationBarrier() {
            @Override public <T> CompletableFuture<T> wait(T value) {
                return CompletableFuture.completedFuture(value);
            }
        };
        Executor sync = Runnable::run;
        try {
            listener.reload(passthrough, mgr,
                InactiveProfiler.INSTANCE, InactiveProfiler.INSTANCE,
                sync, sync).join();
        } catch (Exception e) {
            LOGGER.error("[AdventureItemNames] preload listener '{}' failed: {}",
                listener.getName(), e.toString());
        }
    }

    /**
     * Three-stage lookup for {@code resourcepacks/<packId>}:
     * <ol>
     *   <li>Classpath URL via {@link Class#getResource} on this class —
     *       works in production (shaded jar) and in dev (Architectury
     *       puts common's resources on the runtime classpath).</li>
     *   <li>For jar-scheme URLs, mount the jar's filesystem so {@link Path#of(URI)}
     *       can return a real Path — re-uses the pattern from the per-loader
     *       {@code resolvePackPath} fallback in {@code AdventureItemNamesForge}
     *       / {@code AdventureItemNamesNeoForge}.</li>
     *   <li>Return the parent of {@code pack.mcmeta} so {@link PathPackResources}
     *       walks the right root.</li>
     * </ol>
     * Returns null if the pack isn't on the classpath at all.
     */
    private static Path resolveBundledPackPath(String packId) {
        String mcmetaResource = BASE_MOD_PACK.equals(packId)
            ? "/pack.mcmeta"
            : "/resourcepacks/" + packId + "/pack.mcmeta";
        URL url = BundledPackLoader.class.getResource(mcmetaResource);
        if (url == null) return null;
        try {
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try { FileSystems.newFileSystem(uri, Collections.emptyMap()); }
                catch (Exception ignored) { /* already mounted — fine */ }
            }
            Path mcmeta = Path.of(uri);
            Path root = mcmeta.getParent();
            if (root != null && Files.exists(root)) return root;
        } catch (URISyntaxException | java.nio.file.FileSystemNotFoundException e) {
            LOGGER.warn("[AdventureItemNames] could not resolve bundled pack '{}' from URL {}: {}",
                packId, url, e.toString());
        }
        return null;
    }
}
