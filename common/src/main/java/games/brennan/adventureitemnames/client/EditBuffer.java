package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.EntryOverrides;
import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory uncommitted-edits state for one open config session. The
 * {@code Save to pack} button enables iff {@link #isDirty()} returns
 * true, then flushes through {@link games.brennan.adventureitemnames.internal.UserConfigWriter}.
 *
 * <p>Three parallel buffers — {@code disablePool} for enable/disable
 * toggles, {@code weightOverride} for per-segment per-ref weight edits,
 * and {@code pendingAdded/RemovedEntries} for pool-entry edits (add,
 * remove, edit-text). Reading {@link #effectiveWeight} merges
 * pending → saved → shipped, so the live UI re-renders the % column
 * correctly before the user saves; the entry helpers do the same for
 * the per-pool entry list shown in {@code PoolEntriesScreen}.
 */
@Environment(EnvType.CLIENT)
public final class EditBuffer {

    private final Map<String, Float> pendingWeights = new HashMap<>();
    /** poolId → desired enabled state. */
    private final Map<ResourceLocation, Boolean> pendingPoolEnabled = new HashMap<>();

    /** poolId → entries the user is staging to add. */
    private final Map<ResourceLocation, List<NamePool.PoolEntry>> pendingAddedEntries = new LinkedHashMap<>();
    /** poolId → shipped-entry texts the user is staging to remove. */
    private final Map<ResourceLocation, Set<String>> pendingRemovedEntries = new LinkedHashMap<>();

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

    // ─────────────────────────────────────────────────────────────
    // Pool entry edits
    // ─────────────────────────────────────────────────────────────

    /**
     * Stage a new entry for {@code poolId}. If the same text is sitting
     * in {@code pendingRemovedEntries}, the remove is unstaged instead
     * (the user toggled a delete then re-added the same text). Avoids
     * creating a redundant {@code removed:[X] + added:[X]} pair that
     * lowers to a no-op.
     */
    public void addEntry(ResourceLocation poolId, NamePool.PoolEntry entry) {
        if (poolId == null || entry == null) return;
        Set<String> removed = pendingRemovedEntries.get(poolId);
        if (removed != null && removed.remove(entry.text())) {
            if (removed.isEmpty()) pendingRemovedEntries.remove(poolId);
            return;
        }
        pendingAddedEntries.computeIfAbsent(poolId, k -> new ArrayList<>()).add(entry);
    }

    /** Stage a shipped entry for removal. */
    public void removeShippedEntry(ResourceLocation poolId, String text) {
        if (poolId == null || text == null) return;
        pendingRemovedEntries.computeIfAbsent(poolId, k -> new LinkedHashSet<>()).add(text);
    }

    /** Drop a previously-staged add (by text, first match wins). */
    public void unstageAdd(ResourceLocation poolId, String text) {
        if (poolId == null || text == null) return;
        List<NamePool.PoolEntry> list = pendingAddedEntries.get(poolId);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).text().equals(text)) {
                list.remove(i);
                break;
            }
        }
        if (list.isEmpty()) pendingAddedEntries.remove(poolId);
    }

    /** Drop a previously-staged shipped-entry remove. */
    public void unstageRemove(ResourceLocation poolId, String text) {
        if (poolId == null || text == null) return;
        Set<String> set = pendingRemovedEntries.get(poolId);
        if (set == null) return;
        set.remove(text);
        if (set.isEmpty()) pendingRemovedEntries.remove(poolId);
    }

    /**
     * Smart text-edit routing. When the row being edited was originally
     * shipped (i.e. came from {@code pool.entries()}), stage a remove of
     * the original plus an add of the new text. When the row is a
     * pending-add (the user just added it without saving), mutate that
     * pending-add in place — otherwise the saved JSON would carry a
     * phantom {@code removed:[<original>]} pointing at a text that was
     * never shipped.
     */
    public void editEntryText(ResourceLocation poolId, String originalText, String newText, boolean wasShipped) {
        if (poolId == null || originalText == null || newText == null) return;
        if (originalText.equals(newText)) return;
        if (wasShipped) {
            removeShippedEntry(poolId, originalText);
            addEntry(poolId, NamePool.PoolEntry.universal(newText));
            return;
        }
        List<NamePool.PoolEntry> list = pendingAddedEntries.get(poolId);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            NamePool.PoolEntry e = list.get(i);
            if (e.text().equals(originalText)) {
                list.set(i, new NamePool.PoolEntry(newText, e.itemTypes()));
                return;
            }
        }
    }

    /** Snapshot of pending entry edits as a {@link EntryOverrides} — used by save and by preview promotion. */
    public EntryOverrides snapshotEntryOverrides() {
        EntryOverrides out = new EntryOverrides();
        for (var e : pendingRemovedEntries.entrySet()) {
            for (String t : e.getValue()) out.removeText(e.getKey(), t);
        }
        for (var e : pendingAddedEntries.entrySet()) {
            for (NamePool.PoolEntry pe : e.getValue()) out.addEntry(e.getKey(), pe);
        }
        return out;
    }

    /**
     * Effective entries for {@code pool} with shipped + saved-USER + buffer overlay.
     * Used by {@link PoolEntriesScreen} to render the live row list, and by
     * {@link PoolListScreen}'s "Entries" column to keep the count fresh after edits.
     */
    public List<NamePool.PoolEntry> effectivePoolEntries(NamePool pool) {
        if (pool == null) return List.of();
        List<NamePool.PoolEntry> saved = NamingConfig.effectivePoolEntries(pool);
        ResourceLocation id = pool.id();
        Set<String> bufferRemoved = pendingRemovedEntries.get(id);
        List<NamePool.PoolEntry> bufferAdded = pendingAddedEntries.get(id);
        if ((bufferRemoved == null || bufferRemoved.isEmpty())
            && (bufferAdded == null || bufferAdded.isEmpty())) {
            return saved;
        }
        List<NamePool.PoolEntry> out = new ArrayList<>(saved.size());
        for (NamePool.PoolEntry e : saved) {
            if (bufferRemoved == null || !bufferRemoved.contains(e.text())) out.add(e);
        }
        if (bufferAdded != null) out.addAll(bufferAdded);
        return out;
    }

    public int effectiveEntryCount(NamePool pool) {
        return effectivePoolEntries(pool).size();
    }

    public boolean isPendingAdd(ResourceLocation poolId, String text) {
        List<NamePool.PoolEntry> a = pendingAddedEntries.get(poolId);
        if (a == null) return false;
        for (NamePool.PoolEntry e : a) if (e.text().equals(text)) return true;
        return false;
    }

    public boolean isDirty() {
        return !pendingWeights.isEmpty()
            || !pendingPoolEnabled.isEmpty()
            || !pendingAddedEntries.isEmpty()
            || !pendingRemovedEntries.isEmpty();
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
        pendingAddedEntries.clear();
        pendingRemovedEntries.clear();
    }
}
