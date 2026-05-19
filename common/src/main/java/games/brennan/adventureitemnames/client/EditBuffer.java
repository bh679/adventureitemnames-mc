package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory uncommitted-edits state for one open config session. The
 * {@code Save to pack} button enables iff {@link #isDirty()} returns
 * true, then flushes through {@link games.brennan.adventureitemnames.internal.UserConfigWriter}.
 *
 * <p>Two parallel buffers — {@code disablePool} for enable/disable toggles
 * and {@code weightOverride} for per-segment per-ref weight edits.
 * Reading {@link #effectiveWeight} merges pending → saved → shipped, so
 * the live UI re-renders the % column correctly before the user saves.
 */
@Environment(EnvType.CLIENT)
public final class EditBuffer {

    private final Map<String, Float> pendingWeights = new HashMap<>();
    /** poolId → desired enabled state. */
    private final Map<ResourceLocation, Boolean> pendingPoolEnabled = new HashMap<>();

    public void setWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId, float weight) {
        pendingWeights.put(WeightOverrides.key(chainId, segIdx, refId), weight);
    }

    public void clearWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId) {
        pendingWeights.remove(WeightOverrides.key(chainId, segIdx, refId));
    }

    public void setPoolEnabled(ResourceLocation poolId, boolean enabled) {
        pendingPoolEnabled.put(poolId, enabled);
    }

    public boolean hasPendingWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId) {
        return pendingWeights.containsKey(WeightOverrides.key(chainId, segIdx, refId));
    }

    public Float pendingWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId) {
        return pendingWeights.get(WeightOverrides.key(chainId, segIdx, refId));
    }

    public Boolean pendingPoolEnabled(ResourceLocation poolId) {
        return pendingPoolEnabled.get(poolId);
    }

    public boolean isDirty() {
        return !pendingWeights.isEmpty() || !pendingPoolEnabled.isEmpty();
    }

    public Map<String, Float> snapshotWeights() {
        return new HashMap<>(pendingWeights);
    }

    public Set<ResourceLocation> snapshotDisabledPools() {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (var e : pendingPoolEnabled.entrySet()) {
            if (Boolean.FALSE.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    public Set<ResourceLocation> snapshotEnabledPools() {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (var e : pendingPoolEnabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    public void clear() {
        pendingWeights.clear();
        pendingPoolEnabled.clear();
    }
}
