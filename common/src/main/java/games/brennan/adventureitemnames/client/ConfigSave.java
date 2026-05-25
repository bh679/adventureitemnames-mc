package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSelector;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.ChainAssembler;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.PackChainWriter;
import games.brennan.adventureitemnames.internal.PackChanceWriter;
import games.brennan.adventureitemnames.internal.PackDisableWriter;
import games.brennan.adventureitemnames.internal.PackPaths;
import games.brennan.adventureitemnames.internal.PackPoolWriter;
import games.brennan.adventureitemnames.internal.PackSelectorWriter;
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
import java.util.Optional;
import java.util.Set;

/**
 * Shared {@code Save to pack} flush logic for every config screen that
 * mutates the buffer. Each save unpacks the {@link EditBuffer} into the
 * full {@link UserConfigWriter#save} signature, then re-loads the user
 * layer so runtime queries reflect the new state without a {@code /reload}.
 *
 * <p>In a Loom dev environment ({@link PackPaths#projectRootAvailable()}
 * returns true) every edit category is <em>also</em> baked into the
 * matching pack-side JSON under {@code common/src/main/resources/...} so
 * the edit becomes a committable change to the repo. The corresponding
 * user-config keys are wiped after the bake-in to stop the overlay from
 * double-applying on top of the new shipped data.
 *
 * <p>On success the buffer is cleared and {@code onSuccess} runs (typically
 * a callback that re-disables the Save button and rerolls the preview).
 * On failure the buffer is preserved so the user can retry.
 */
@Environment(EnvType.CLIENT)
public final class ConfigSave {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BASE_PACK_ID = "mod/adventureitemnames";

    private ConfigSave() {}

    /** Flush every pending edit in {@code buffer} to disk. */
    public static boolean commit(EditBuffer buffer, Runnable onSuccess) {
        Set<ResourceLocation> disabledPools     = buffer.snapshotDisabledPools();
        Set<ResourceLocation> enabledPools      = buffer.snapshotEnabledPools();
        Set<ResourceLocation> disabledSelectors = buffer.snapshotDisabledSelectors();
        Set<ResourceLocation> enabledSelectors  = buffer.snapshotEnabledSelectors();
        Map<String, Float> weights              = buffer.snapshotWeights();
        Map<ChanceKind, Float> chances          = buffer.snapshotChances();
        Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> selectorTiers = buffer.snapshotSelectorTiers();
        Map<ResourceLocation, NameSelector> customSelectors = buffer.snapshotCustomSelectors();
        Set<ResourceLocation> removedCustomSelectorIds      = buffer.snapshotRemovedCustomSelectorIds();

        boolean ok = UserConfigWriter.save(
            disabledPools,
            enabledPools,
            disabledSelectors,
            enabledSelectors,
            weights,
            buffer.snapshotEntryOverrides(),
            chances,
            selectorTiers,
            buffer.snapshotSegmentEdits(),
            buffer.snapshotSegmentResets(),
            buffer.snapshotAppendedSegments(),
            buffer.snapshotSegmentOrder(),
            customSelectors,
            removedCustomSelectorIds);
        if (!ok) {
            LOGGER.warn("[AdventureItemNames] save failed — pending edits retained");
            return false;
        }
        UserConfigLoader.reload();

        boolean packWritten = false;
        if (PackPaths.projectRootAvailable()) {
            Set<ResourceLocation> dirtyChains = collectDirtyChains(buffer, weights);
            Set<ResourceLocation> dirtyPools = collectDirtyPools(buffer);
            Map<ResourceLocation, String> newChainPacks = buffer.snapshotPendingNewChains();

            if (!dirtyChains.isEmpty()) {
                writePackFilesForDirtyChains(dirtyChains, newChainPacks);
                UserConfigWriter.wipeChainSegmentData(dirtyChains);
                UserConfigWriter.wipeWeightData(dirtyChains);
                packWritten = true;
            }
            if (!dirtyPools.isEmpty()) {
                writePackFilesForDirtyPools(dirtyPools);
                UserConfigWriter.wipePoolEntryData(dirtyPools);
                packWritten = true;
            }
            if (!disabledPools.isEmpty() || !enabledPools.isEmpty()
                || !disabledSelectors.isEmpty() || !enabledSelectors.isEmpty()) {
                if (PackDisableWriter.writeDisables(BASE_PACK_ID,
                    disabledPools, enabledPools,
                    Set.of(), Set.of(),
                    disabledSelectors, enabledSelectors)) {
                    UserConfigWriter.wipeDisableData(disabledPools, enabledPools,
                        disabledSelectors, enabledSelectors);
                    packWritten = true;
                }
            }
            if (!chances.isEmpty()) {
                if (PackChanceWriter.writeChances(BASE_PACK_ID, chances)) {
                    UserConfigWriter.wipeChanceData(chances.keySet());
                    packWritten = true;
                }
            }
            if (!selectorTiers.isEmpty()) {
                Set<ResourceLocation> selectorsWritten = writePackFilesForSelectorTiers(selectorTiers);
                if (!selectorsWritten.isEmpty()) {
                    UserConfigWriter.wipeSelectorTierData(selectorsWritten);
                    packWritten = true;
                }
            }
            if (!customSelectors.isEmpty() || !removedCustomSelectorIds.isEmpty()) {
                Set<ResourceLocation> wiped = writePackFilesForCustomSelectors(customSelectors, removedCustomSelectorIds);
                if (!wiped.isEmpty()) {
                    UserConfigWriter.wipeCustomSelectorData(wiped);
                    packWritten = true;
                }
            }

            // One reload of user-config at the end so the wiped sections
            // stop double-applying. The async server data reload below
            // picks up the new pack-side JSON.
            if (packWritten) UserConfigLoader.reload();
        }

        buffer.clear();
        if (onSuccess != null) onSuccess.run();
        LOGGER.info("[AdventureItemNames] user config saved");
        if (packWritten) {
            triggerDataReload();
        }
        return true;
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
            for (String stalePack : previousPacks) {
                PackChainWriter.deleteChain(stalePack, chainId.getPath());
            }
            NameRegistry.putChainInMemory(effective);
        }
    }

    /**
     * For each pool in {@code dirty}, materialise the effective post-edit
     * {@link NamePool} from {@link NamingConfig#effectivePoolEntries}
     * (which now reflects the just-reloaded user-config layer), resolve
     * the winning source pack via {@link NameRegistry#packIdOfPool},
     * canonicalise the pack id, and call {@link PackPoolWriter#writePool}.
     */
    private static void writePackFilesForDirtyPools(Set<ResourceLocation> dirty) {
        for (ResourceLocation poolId : dirty) {
            NamePool shipped = NameRegistry.pool(poolId).orElse(null);
            if (shipped == null) {
                LOGGER.warn("[AdventureItemNames] pool {} not registered — skipping pack write", poolId);
                continue;
            }
            java.util.List<NamePool.PoolEntry> effective = NamingConfig.effectivePoolEntries(shipped);
            NamePool postEdit = new NamePool(poolId, java.util.List.copyOf(effective));
            String packId = PackPaths.canonicalize(NameRegistry.packIdOfPool(poolId));
            if (PackPoolWriter.writePool(packId, postEdit)) {
                NameRegistry.putPoolInMemory(postEdit);
            }
        }
    }

    /**
     * For each selector id with pending tier overrides, resolve its
     * owning pack, write the merged tier overrides into the existing
     * selector file (or create a new file if the selector is user-side),
     * and return the set of selectors that wrote successfully so the
     * caller can wipe their user-config overlays.
     */
    private static Set<ResourceLocation> writePackFilesForSelectorTiers(
            Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> edits) {
        Set<ResourceLocation> ok = new LinkedHashSet<>();
        for (var entry : edits.entrySet()) {
            ResourceLocation selectorId = entry.getKey();
            String packId = PackPaths.canonicalize(NameRegistry.packIdOfSelector(selectorId));
            if (PackSelectorWriter.writeSelectorTiers(packId, selectorId, entry.getValue())) {
                ok.add(selectorId);
            }
        }
        return ok;
    }

    /**
     * Write a new selector file for every added custom selector and
     * delete the file for every removed one. Both groups land in the
     * base mod's pack tree (matching the {@code + New chain} popup
     * default). Returns the union of selector ids the writer touched so
     * the caller can wipe their user-config overlays.
     */
    private static Set<ResourceLocation> writePackFilesForCustomSelectors(
            Map<ResourceLocation, NameSelector> added, Set<ResourceLocation> removed) {
        Set<ResourceLocation> touched = new LinkedHashSet<>();
        for (var entry : added.entrySet()) {
            if (PackSelectorWriter.writeSelector(BASE_PACK_ID, entry.getValue())) {
                touched.add(entry.getKey());
            }
        }
        for (ResourceLocation id : removed) {
            if (PackSelectorWriter.deleteSelector(BASE_PACK_ID, id)) {
                touched.add(id);
            }
        }
        return touched;
    }

    /**
     * Pool ids touched by this session's entry edits — union of the
     * {@code added} and {@code removed} keysets on the buffer's
     * {@link games.brennan.adventureitemnames.internal.EntryOverrides}
     * snapshot.
     */
    private static Set<ResourceLocation> collectDirtyPools(EditBuffer buffer) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        var snap = buffer.snapshotEntryOverrides();
        out.addAll(snap.removed.keySet());
        out.addAll(snap.added.keySet());
        return out;
    }

    /**
     * Union of chain ids referenced by every segment-shape buffer slot
     * plus chain ids extracted from weight-override keys (weights are
     * baked into chain JSON via {@link ChainAssembler}).
     */
    private static Set<ResourceLocation> collectDirtyChains(EditBuffer buffer, Map<String, Float> weights) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (String key : buffer.snapshotSegmentEdits().keySet()) addChainFromSegmentKey(out, key);
        for (String key : buffer.snapshotSegmentResets()) addChainFromSegmentKey(out, key);
        for (String chainStr : buffer.snapshotAppendedSegments().keySet()) addChainFromString(out, chainStr);
        for (String chainStr : buffer.snapshotSegmentOrder().keySet()) addChainFromString(out, chainStr);
        out.addAll(buffer.snapshotPendingNewChains().keySet());
        // Weight-override keys are "<chain>#<seg>#<ref>" — pull the chain.
        for (String key : weights.keySet()) addChainFromWeightKey(out, key);
        return out;
    }

    private static void addChainFromSegmentKey(Set<ResourceLocation> out, String key) {
        int hash = key.indexOf('#');
        if (hash <= 0) return;
        ResourceLocation rl = ResourceLocation.tryParse(key.substring(0, hash));
        if (rl != null) out.add(rl);
    }

    private static void addChainFromWeightKey(Set<ResourceLocation> out, String key) {
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
