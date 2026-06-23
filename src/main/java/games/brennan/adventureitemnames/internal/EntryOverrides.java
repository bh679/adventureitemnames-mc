package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-pool add/remove edits to the entries of a {@link NamePool} for one
 * layer (user-config / API). Sibling of {@link WeightOverrides} and
 * {@link DisableSet}, layered on the same three-layer
 * {@code NamingConfig} model.
 *
 * <p>Two parallel maps keyed by pool id: {@code removed} carries the
 * texts of shipped entries the user wants gone (exact-text match;
 * removes all duplicates if the upstream pool somehow has them); and
 * {@code added} carries new {@link NamePool.PoolEntry} instances to
 * append to the effective entry list. An inline-text-edit in the UI
 * lowers to one {@code removed} text plus one {@code added} entry.
 *
 * <p>{@code isEmptyFor(poolId)} backs the fast-path inside
 * {@code NamingConfig.effectivePoolEntries} — when both the USER and
 * API layers report empty for a given pool, the composer iterates the
 * shipped entries directly with no allocation. Hot path stays clean.
 *
 * <p>Not thread-safe — callers hold the {@code NamingConfig} lock.
 */
public final class EntryOverrides {

    public final Map<ResourceLocation, Set<String>> removed = new LinkedHashMap<>();
    public final Map<ResourceLocation, List<NamePool.PoolEntry>> added = new LinkedHashMap<>();

    public void clear() {
        removed.clear();
        added.clear();
    }

    public void mergeFrom(EntryOverrides other) {
        if (other == null) return;
        for (var e : other.removed.entrySet()) {
            removed.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
        }
        for (var e : other.added.entrySet()) {
            added.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
    }

    public boolean isEmpty() {
        return removed.isEmpty() && added.isEmpty();
    }

    /** True when no edits apply to {@code poolId} — backs the composer fast-path. */
    public boolean isEmptyFor(ResourceLocation poolId) {
        Set<String> r = removed.get(poolId);
        List<NamePool.PoolEntry> a = added.get(poolId);
        return (r == null || r.isEmpty()) && (a == null || a.isEmpty());
    }

    /** Deep-ish copy: lists/sets duplicated; entry records are value types so reused safely. */
    public EntryOverrides snapshot() {
        EntryOverrides out = new EntryOverrides();
        for (var e : removed.entrySet()) {
            out.removed.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        for (var e : added.entrySet()) {
            out.added.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    public void addEntry(ResourceLocation poolId, NamePool.PoolEntry entry) {
        if (poolId == null || entry == null) return;
        added.computeIfAbsent(poolId, k -> new ArrayList<>()).add(entry);
    }

    public void removeText(ResourceLocation poolId, String text) {
        if (poolId == null || text == null) return;
        removed.computeIfAbsent(poolId, k -> new LinkedHashSet<>()).add(text);
    }

    public void clearForPool(ResourceLocation poolId) {
        if (poolId == null) return;
        removed.remove(poolId);
        added.remove(poolId);
    }
}
