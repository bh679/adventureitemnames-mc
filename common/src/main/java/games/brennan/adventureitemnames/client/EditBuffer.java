package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory uncommitted-edits state for one open config session. The
 * {@code Save to pack} button enables iff {@link #isDirty()} returns
 * true, then flushes through {@code ConfigSave} →
 * {@link games.brennan.adventureitemnames.internal.UserConfigWriter}.
 *
 * <p>Five parallel buffers — weights (per chain-segment-ref), pool
 * enable/disable (v1), and three new in v2: chance overrides per
 * {@link ChanceKind}, selector tier remappings, and selector
 * enable/disable. {@link #effectiveChance(ChanceKind)} merges pending →
 * user → default; {@link #effectiveTierChain(ResourceLocation, String, ResourceLocation)}
 * merges pending → user → shipped — so a UI row re-renders correctly
 * before the user hits Save.
 */
@Environment(EnvType.CLIENT)
public final class EditBuffer {

    private final Map<String, Float> pendingWeights = new HashMap<>();
    /** poolId → desired enabled state. */
    private final Map<ResourceLocation, Boolean> pendingPoolEnabled = new HashMap<>();

    private final Map<ChanceKind, Float> pendingChances = new EnumMap<>(ChanceKind.class);
    /** selectorId → tier → Optional<chainId> (empty = (none) suppression). */
    private final Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> pendingSelectorTiers = new HashMap<>();
    private final Map<ResourceLocation, Boolean> pendingSelectorEnabled = new HashMap<>();

    // ────────────────────────────────────────────────────────────
    // Weights (v1)
    // ────────────────────────────────────────────────────────────

    public void setWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId, float weight) {
        pendingWeights.put(WeightOverrides.key(chainId, segIdx, refId), weight);
    }

    public void clearWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId) {
        pendingWeights.remove(WeightOverrides.key(chainId, segIdx, refId));
    }

    public boolean hasPendingWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId) {
        return pendingWeights.containsKey(WeightOverrides.key(chainId, segIdx, refId));
    }

    public Float pendingWeight(ResourceLocation chainId, int segIdx, ResourceLocation refId) {
        return pendingWeights.get(WeightOverrides.key(chainId, segIdx, refId));
    }

    public Map<String, Float> snapshotWeights() {
        return new HashMap<>(pendingWeights);
    }

    // ────────────────────────────────────────────────────────────
    // Pool enable/disable (v1)
    // ────────────────────────────────────────────────────────────

    public void setPoolEnabled(ResourceLocation poolId, boolean enabled) {
        pendingPoolEnabled.put(poolId, enabled);
    }

    public Boolean pendingPoolEnabled(ResourceLocation poolId) {
        return pendingPoolEnabled.get(poolId);
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

    // ────────────────────────────────────────────────────────────
    // Chances (v2)
    // ────────────────────────────────────────────────────────────

    /** Stage a chance override. Pass a value outside {@code [0, 1]} to clear. */
    public void setChance(ChanceKind kind, float value) {
        if (kind == null) return;
        if (value < 0f || value > 1f) {
            pendingChances.remove(kind);
            return;
        }
        pendingChances.put(kind, value);
    }

    /** Reset a chance to its default — also clears any saved user-layer override on commit. */
    public void clearChance(ChanceKind kind) {
        if (kind == null) return;
        // Mark with a negative sentinel so the writer drops the key on save.
        pendingChances.put(kind, -1f);
    }

    public boolean hasPendingChance(ChanceKind kind) {
        return pendingChances.containsKey(kind);
    }

    /** Effective value for the UI: pending → user-layer → default. */
    public float effectiveChance(ChanceKind kind) {
        Float pending = pendingChances.get(kind);
        if (pending != null) {
            if (pending < 0f) return kind.defaultValue();
            return pending;
        }
        Map<ChanceKind, Float> userSnap = NamingConfig.snapshotUserChances();
        Float user = userSnap.get(kind);
        if (user != null) return user;
        return kind.defaultValue();
    }

    public Map<ChanceKind, Float> snapshotChances() {
        return new EnumMap<>(pendingChances);
    }

    // ────────────────────────────────────────────────────────────
    // Selector tier remap (v2)
    // ────────────────────────────────────────────────────────────

    /**
     * Stage a tier override. {@code Optional.empty()} represents
     * {@code (none)}; pass {@code null} to clear (reverts to shipped).
     */
    public void setSelectorTier(ResourceLocation selectorId, String tierKey,
                                Optional<ResourceLocation> chainId) {
        if (selectorId == null || tierKey == null) return;
        if (chainId == null) {
            Map<String, Optional<ResourceLocation>> per = pendingSelectorTiers.get(selectorId);
            if (per != null) {
                per.remove(tierKey);
                if (per.isEmpty()) pendingSelectorTiers.remove(selectorId);
            }
            return;
        }
        pendingSelectorTiers.computeIfAbsent(selectorId, k -> new LinkedHashMap<>()).put(tierKey, chainId);
    }

    /** Effective tier chain for the UI: pending → user-layer → shipped. */
    public Optional<ResourceLocation> effectiveTierChain(ResourceLocation selectorId, String tierKey,
                                                         ResourceLocation shippedChainId) {
        Map<String, Optional<ResourceLocation>> per = pendingSelectorTiers.get(selectorId);
        if (per != null && per.containsKey(tierKey)) {
            return per.get(tierKey);
        }
        return NamingConfig.effectiveTierChain(selectorId, tierKey, shippedChainId);
    }

    public Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> snapshotSelectorTiers() {
        Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> copy = new LinkedHashMap<>();
        for (var e : pendingSelectorTiers.entrySet()) {
            copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return copy;
    }

    // ────────────────────────────────────────────────────────────
    // Selector enable/disable (v2)
    // ────────────────────────────────────────────────────────────

    public void setSelectorEnabled(ResourceLocation selectorId, boolean enabled) {
        pendingSelectorEnabled.put(selectorId, enabled);
    }

    public Boolean pendingSelectorEnabled(ResourceLocation selectorId) {
        return pendingSelectorEnabled.get(selectorId);
    }

    public Set<ResourceLocation> snapshotDisabledSelectors() {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (var e : pendingSelectorEnabled.entrySet()) {
            if (Boolean.FALSE.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    public Set<ResourceLocation> snapshotEnabledSelectors() {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (var e : pendingSelectorEnabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    // ────────────────────────────────────────────────────────────
    // Common
    // ────────────────────────────────────────────────────────────

    public boolean isDirty() {
        return !pendingWeights.isEmpty()
            || !pendingPoolEnabled.isEmpty()
            || !pendingChances.isEmpty()
            || !pendingSelectorTiers.isEmpty()
            || !pendingSelectorEnabled.isEmpty();
    }

    public void clear() {
        pendingWeights.clear();
        pendingPoolEnabled.clear();
        pendingChances.clear();
        pendingSelectorTiers.clear();
        pendingSelectorEnabled.clear();
    }
}
