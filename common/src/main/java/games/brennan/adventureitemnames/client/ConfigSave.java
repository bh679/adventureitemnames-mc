package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.internal.ChainAssembler;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.PackChainWriter;
import games.brennan.adventureitemnames.internal.PackPaths;
import games.brennan.adventureitemnames.internal.PerPackSplitter;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.internal.UserConfigWriter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared {@code Save to pack} flush logic for every config screen that
 * mutates the buffer. Each save unpacks the {@link EditBuffer} into the
 * full v1+v2 {@link UserConfigWriter#save} signature, then re-loads the
 * user layer so runtime queries reflect the new state without a {@code /reload}.
 *
 * <p>On success the buffer is cleared and {@code onSuccess} runs (typically
 * a callback that re-disables the Save button and rerolls the preview).
 * On failure the buffer is preserved so the user can retry.
 */
@Environment(EnvType.CLIENT)
public final class ConfigSave {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConfigSave() {}

    /** Flush every pending edit in {@code buffer} to disk. */
    public static boolean commit(EditBuffer buffer, Runnable onSuccess) {
        boolean ok = UserConfigWriter.save(
            buffer.snapshotDisabledPools(),
            buffer.snapshotEnabledPools(),
            buffer.snapshotDisabledSelectors(),
            buffer.snapshotEnabledSelectors(),
            buffer.snapshotWeights(),
            buffer.snapshotEntryOverrides(),
            buffer.snapshotChances(),
            buffer.snapshotSelectorTiers(),
            buffer.snapshotSegmentEdits(),
            buffer.snapshotSegmentResets(),
            buffer.snapshotAppendedSegments(),
            buffer.snapshotSegmentOrder(),
            buffer.snapshotCustomSelectors(),
            buffer.snapshotRemovedCustomSelectorIds());
        if (ok) {
            UserConfigLoader.reload();
            // Dev-mode datapack-editor write: for every chain touched this
            // session, materialise the post-edit chain (with the just-reloaded
            // user-config overrides applied on top of shipped) and split it
            // per source pack, writing each pack's layer file.
            Set<ResourceLocation> dirtyChains = collectDirtyChains(buffer);
            Map<ResourceLocation, String> newChainPacks = buffer.snapshotPendingNewChains();
            boolean packWritten = false;
            if (PackPaths.projectRootAvailable() && !dirtyChains.isEmpty()) {
                writePackFilesForDirtyChains(dirtyChains, newChainPacks);
                // The user-config segment overlays are now baked into the
                // pack files. Strip them from user-config so they stop
                // double-applying on top of the new shipped data — and
                // trigger a resource reload so the in-game state picks up
                // the new pack files immediately.
                UserConfigWriter.wipeChainSegmentData(dirtyChains);
                UserConfigLoader.reload();
                packWritten = true;
            }
            buffer.clear();
            if (onSuccess != null) onSuccess.run();
            LOGGER.info("[AdventureItemNames] user config saved");
            if (packWritten) {
                triggerDataReload();
            }
        } else {
            LOGGER.warn("[AdventureItemNames] save failed — pending edits retained");
        }
        return ok;
    }

    /**
     * For each chain in {@code dirty}, materialise the effective post-edit
     * {@link NameChain} via {@link ChainAssembler}, split it per source
     * pack via {@link PerPackSplitter}, and write each pack's layer to
     * its own {@code naming/chains/<path>.json} file. Packs that
     * previously contributed but now have no refs get their file deleted.
     *
     * <p>{@code newChainPacks} carries any session-created chains whose
     * base pack the user picked explicitly via the {@code + New chain}
     * popup — passed through to {@link PerPackSplitter#split(NameChain, String)}
     * so the new chain's metadata file lands in the user's chosen pack
     * instead of always defaulting to the base mod.
     */
    private static void writePackFilesForDirtyChains(Set<ResourceLocation> dirty,
                                                     Map<ResourceLocation, String> newChainPacks) {
        for (ResourceLocation chainId : dirty) {
            NameChain effective = ChainAssembler.assembleEffective(chainId);
            if (effective == null) {
                LOGGER.warn("[AdventureItemNames] chain {} not registered — skipping pack write", chainId);
                continue;
            }
            String basePackOverride = newChainPacks.get(chainId);
            Map<String, PerPackSplitter.PackLayer> split = PerPackSplitter.split(effective, basePackOverride);
            // Previously-contributing packs whose contribution is now empty
            // should have their file deleted so the runtime doesn't keep
            // loading a stale layer. Canonicalize the previous-pack ids so
            // Loom's synthetic "generated_<hash>" alias collapses to the
            // same key the splitter emits — otherwise the diff below would
            // call deleteChain on a path the writer just populated.
            Set<String> previousPacks = new LinkedHashSet<>();
            for (String p : NameRegistry.packsOfChain(chainId)) {
                previousPacks.add(PackPaths.canonicalize(p));
            }
            for (var layer : split.values()) {
                if (layer.empty()) {
                    PackChainWriter.deleteChain(layer.packId(), chainId.getPath());
                } else {
                    PackChainWriter.writeChain(layer.packId(), layer.chain(), layer.replace());
                }
                previousPacks.remove(layer.packId());
            }
            // Packs that contributed previously but aren't in the new split
            // at all also get their file deleted.
            for (String stalePack : previousPacks) {
                PackChainWriter.deleteChain(stalePack, chainId.getPath());
            }
            // Patch the in-memory chain immediately so the UI reflects the
            // new state on the next render. The async server data reload
            // below will eventually re-derive the same chain from disk.
            NameRegistry.putChainInMemory(effective);
        }
    }

    /** Union of chain ids referenced by every segment-shape buffer slot. */
    private static Set<ResourceLocation> collectDirtyChains(EditBuffer buffer) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (String key : buffer.snapshotSegmentEdits().keySet()) addChainFromSegmentKey(out, key);
        for (String key : buffer.snapshotSegmentResets()) addChainFromSegmentKey(out, key);
        for (String chainStr : buffer.snapshotAppendedSegments().keySet()) addChainFromString(out, chainStr);
        for (String chainStr : buffer.snapshotSegmentOrder().keySet()) addChainFromString(out, chainStr);
        out.addAll(buffer.snapshotPendingNewChains().keySet());
        return out;
    }

    private static void addChainFromSegmentKey(Set<ResourceLocation> out, String key) {
        // Segment-override keys are "<chain_id>#<segment_index>" — strip the suffix.
        int hash = key.indexOf('#');
        if (hash <= 0) return;
        ResourceLocation rl = ResourceLocation.tryParse(key.substring(0, hash));
        if (rl != null) out.add(rl);
    }

    private static void addChainFromString(Set<ResourceLocation> out, String chainStr) {
        ResourceLocation rl = ResourceLocation.tryParse(chainStr);
        if (rl != null) out.add(rl);
    }

    /**
     * Trigger the integrated server's data reload so the chain listeners
     * pick up the freshly-written pack files. {@code Minecraft#reloadResourcePacks}
     * only handles client-side resources (textures / models); chain JSON
     * lives under {@code data/} which is reloaded by the server-side
     * reload listener chain — same path the {@code /reload} command takes.
     *
     * <p>Fire-and-forget: the reload runs async on the server thread and
     * takes a couple of seconds. The UI doesn't block on completion.
     */
    private static void triggerDataReload() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[AdventureItemNames] no integrated server — chain data won't reload until next /reload or world load");
            return;
        }
        server.execute(() ->
            server.reloadResources(server.getPackRepository().getSelectedIds())
                .exceptionally(ex -> {
                    LOGGER.warn("[AdventureItemNames] server data reload failed: {}", ex.getMessage());
                    return null;
                }));
    }
}
