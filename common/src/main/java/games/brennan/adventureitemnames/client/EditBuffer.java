package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NameSelector;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.EntryOverrides;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory uncommitted-edits state for one open config session. The
 * {@code Save to pack} button enables iff {@link #isDirty()} returns
 * true, then flushes through {@code ConfigSave} →
 * {@link games.brennan.adventureitemnames.internal.UserConfigWriter}.
 *
 * <p>Six parallel buffers — weights (per chain-segment-ref), pool
 * enable/disable, pool entry add/remove (v3 in-game pool editor), and
 * three v2 buffers: chance overrides per {@link ChanceKind}, selector
 * tier remappings, and selector enable/disable. The {@code effective…}
 * helpers merge pending → user → shipped so any UI row re-renders
 * correctly before the user hits Save.
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

    private final Map<ChanceKind, Float> pendingChances = new EnumMap<>(ChanceKind.class);
    /**
     * Pending color overrides. A key present with a {@code null} value signals
     * "clear" (sentinel mirroring the negative-float sentinel used for chances);
     * a non-null value is a staged override.
     */
    private final Map<ChanceKind, ChatFormatting> pendingColors = new EnumMap<>(ChanceKind.class);
    /** selectorId → tier → Optional<chainId> (empty = (none) suppression). */
    private final Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> pendingSelectorTiers = new HashMap<>();
    private final Map<ResourceLocation, Boolean> pendingSelectorEnabled = new HashMap<>();

    /** chainId#segIdx → SegmentEdit (each field nullable). */
    private final Map<String, SegmentOverrides.SegmentEdit> pendingSegmentEdits = new LinkedHashMap<>();
    /** chainId.toString() → ordered list of segments appended in this session. */
    private final Map<String, List<NameSegment>> pendingAppendedSegments = new LinkedHashMap<>();
    /**
     * Segment keys whose entire {@code segment_overrides[key]} object should
     * be deleted from the on-disk config (reset / revert). Processed BEFORE
     * {@link #pendingSegmentEdits} so reset+edit in one session ends up with
     * just the new edits.
     */
    private final Set<String> pendingSegmentResets = new LinkedHashSet<>();
    /**
     * chainId.toString() → user-modified display order (permutation of original indices).
     * Populated lazily when the user first reorders a chain's segments —
     * starts as the chain's current effective order.
     */
    private final Map<String, List<Integer>> pendingSegmentOrder = new LinkedHashMap<>();
    /** New custom selectors added in this session. Insertion-ordered. */
    private final Map<ResourceLocation, NameSelector> pendingCustomSelectors = new LinkedHashMap<>();
    /** Custom-selector ids the user removed in this session. */
    private final Set<ResourceLocation> pendingRemovedCustomSelectorIds = new LinkedHashSet<>();

    /**
     * Brand-new chains the user created in this session via the
     * {@code + New chain} popup. Keyed by chain id, value is the target
     * pack id (e.g. {@code mod/adventureitemnames/wholesome}) so the
     * {@link games.brennan.adventureitemnames.internal.PerPackSplitter}
     * can override the default base-pack resolution for that id.
     */
    private final Map<ResourceLocation, String> pendingNewChains = new LinkedHashMap<>();

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

    // ─────────────────────────────────────────────────────────────
    // Pool entry edits (v3)
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
    // Colors (per ChanceKind row)
    // ────────────────────────────────────────────────────────────

    /** Stage a color override. Pass {@code null} to clear the color (revert to default styling). */
    public void setColor(ChanceKind kind, ChatFormatting color) {
        if (kind == null) return;
        pendingColors.put(kind, color);
    }

    /** Reset a color to its default — also clears any saved user-layer override on commit. */
    public void clearColor(ChanceKind kind) {
        if (kind == null) return;
        pendingColors.put(kind, null);
    }

    public boolean hasPendingColor(ChanceKind kind) {
        return pendingColors.containsKey(kind);
    }

    /**
     * Effective color for the UI: pending → user-layer → empty.
     * Empty means no color override; the row's swatch should show "(default)".
     */
    public Optional<ChatFormatting> effectiveColor(ChanceKind kind) {
        if (pendingColors.containsKey(kind)) {
            ChatFormatting pending = pendingColors.get(kind);
            return Optional.ofNullable(pending);
        }
        Map<ChanceKind, ChatFormatting> userSnap = NamingConfig.snapshotUserColors();
        return Optional.ofNullable(userSnap.get(kind));
    }

    public Map<ChanceKind, ChatFormatting> snapshotColors() {
        return new EnumMap<>(pendingColors);
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
    // Per-segment field overrides (v3)
    // ────────────────────────────────────────────────────────────

    public void setSegmentChance(ResourceLocation chainId, int segIdx, Float chance) {
        mutateSegment(chainId, segIdx, edit -> edit.withChance(chance));
    }

    public void setSegmentConnection(ResourceLocation chainId, int segIdx, String connection) {
        mutateSegment(chainId, segIdx, edit -> edit.withConnection(connection));
    }

    public void setSegmentNewline(ResourceLocation chainId, int segIdx, Boolean newline) {
        mutateSegment(chainId, segIdx, edit -> edit.withNewline(newline));
    }

    /** Pass {@code null} to clear the pending label edit; empty string is a legitimate label value. */
    public void setSegmentLabel(ResourceLocation chainId, int segIdx, String label) {
        mutateSegment(chainId, segIdx, edit -> edit.withLabel(label));
    }

    public String effectiveSegmentLabel(ResourceLocation chainId, int segIdx, String shipped) {
        SegmentOverrides.SegmentEdit pending = pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
        if (pending != null && pending.label() != null) return pending.label();
        return NamingConfig.effectiveSegmentLabel(chainId, segIdx, shipped);
    }

    /** Pass {@code null} to clear the refs override and fall through to the shipped list. */
    public void setSegmentRefs(ResourceLocation chainId, int segIdx, List<NameSegment.WeightedRef> refs) {
        mutateSegment(chainId, segIdx, edit -> edit.withRefs(refs == null ? null : List.copyOf(refs)));
    }

    /** Read the pending {@link SegmentOverrides.SegmentEdit} for this segment, or {@code null}. */
    public SegmentOverrides.SegmentEdit pendingSegmentEdit(ResourceLocation chainId, int segIdx) {
        return pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
    }

    public boolean hasPendingSegmentEdit(ResourceLocation chainId, int segIdx) {
        return pendingSegmentEdits.containsKey(SegmentOverrides.key(chainId, segIdx));
    }

    /** Effective chance for the UI: pending → user-layer → shipped. */
    public float effectiveSegmentChance(ResourceLocation chainId, int segIdx, float shipped) {
        SegmentOverrides.SegmentEdit pending = pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
        if (pending != null && pending.chance() != null) return clamp01(pending.chance());
        return NamingConfig.effectiveSegmentChance(chainId, segIdx, shipped);
    }

    public String effectiveSegmentConnection(ResourceLocation chainId, int segIdx, String shipped) {
        SegmentOverrides.SegmentEdit pending = pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
        if (pending != null && pending.connection() != null) return pending.connection();
        return NamingConfig.effectiveSegmentConnection(chainId, segIdx, shipped);
    }

    public boolean effectiveSegmentNewline(ResourceLocation chainId, int segIdx, boolean shipped) {
        SegmentOverrides.SegmentEdit pending = pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
        if (pending != null && pending.newline() != null) return pending.newline();
        return NamingConfig.effectiveSegmentNewline(chainId, segIdx, shipped);
    }

    public List<NameSegment.WeightedRef> effectiveSegmentRefs(ResourceLocation chainId, int segIdx,
                                                              List<NameSegment.WeightedRef> shipped) {
        SegmentOverrides.SegmentEdit pending = pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
        if (pending != null && pending.refs() != null) return List.copyOf(pending.refs());
        return NamingConfig.effectiveSegmentRefs(chainId, segIdx, shipped);
    }

    public Map<String, SegmentOverrides.SegmentEdit> snapshotSegmentEdits() {
        return new LinkedHashMap<>(pendingSegmentEdits);
    }

    /**
     * Mark a segment for removal so the composer skips it. Index applies
     * to the effective segment range: {@code 0..(shippedCount + appendedCount - 1)}.
     * Pass {@code false} to clear the flag.
     */
    public void setSegmentRemoved(ResourceLocation chainId, int segIdx, boolean removed) {
        mutateSegment(chainId, segIdx, edit -> edit.withRemoved(removed ? Boolean.TRUE : null));
    }

    /**
     * Append a brand-new segment to {@code chainId}. The new segment's
     * effective index is {@code shippedCount + appendedCount(before)}.
     * Returns the index assigned to the new segment within the appended
     * list (zero-based).
     */
    public int appendSegment(ResourceLocation chainId, NameSegment seg) {
        if (chainId == null || seg == null) return -1;
        List<NameSegment> list = pendingAppendedSegments.computeIfAbsent(
            chainId.toString(), k -> new ArrayList<>());
        list.add(seg);
        return list.size() - 1;
    }

    /**
     * Remove an appended segment by its index in the appended list
     * (zero-based within that list, NOT the effective chain index).
     */
    public void removeAppendedSegment(ResourceLocation chainId, int appendedIndex) {
        if (chainId == null) return;
        List<NameSegment> list = pendingAppendedSegments.get(chainId.toString());
        if (list == null || appendedIndex < 0 || appendedIndex >= list.size()) return;
        list.remove(appendedIndex);
        if (list.isEmpty()) pendingAppendedSegments.remove(chainId.toString());
    }

    public List<NameSegment> pendingAppendedSegments(ResourceLocation chainId) {
        if (chainId == null) return List.of();
        List<NameSegment> list = pendingAppendedSegments.get(chainId.toString());
        return list == null ? List.of() : List.copyOf(list);
    }

    /** True when this segment has been marked as removed in the pending buffer. */
    public boolean isSegmentRemovedPending(ResourceLocation chainId, int segIdx) {
        SegmentOverrides.SegmentEdit pending = pendingSegmentEdits.get(SegmentOverrides.key(chainId, segIdx));
        return pending != null && Boolean.TRUE.equals(pending.removed());
    }

    public Map<String, List<NameSegment>> snapshotAppendedSegments() {
        Map<String, List<NameSegment>> out = new LinkedHashMap<>(pendingAppendedSegments.size());
        for (var e : pendingAppendedSegments.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    /**
     * Mark a segment for a full reset on save — the entire
     * {@code segment_overrides[<chainId#segIdx>]} object will be removed
     * from disk, reverting the segment to its shipped behaviour. Also
     * drops any pending field edits for the same segment.
     */
    public void resetSegment(ResourceLocation chainId, int segIdx) {
        if (chainId == null) return;
        String key = SegmentOverrides.key(chainId, segIdx);
        pendingSegmentEdits.remove(key);
        pendingSegmentResets.add(key);
    }

    public Set<String> snapshotSegmentResets() {
        return new LinkedHashSet<>(pendingSegmentResets);
    }

    /**
     * Effective display order for the chain editor: pending → user-config
     * → identity. {@code totalCount} is the chain's effective segment
     * count including buffer-pending appends, so the returned list always
     * has that exact size when consistent.
     */
    public List<Integer> effectiveSegmentOrder(ResourceLocation chainId, int totalCount) {
        if (chainId == null || totalCount <= 0) return java.util.Collections.emptyList();
        List<Integer> pending = pendingSegmentOrder.get(chainId.toString());
        if (pending != null && pending.size() == totalCount) return List.copyOf(pending);
        return NamingConfig.effectiveSegmentOrder(chainId, totalCount);
    }

    /**
     * Move the segment currently shown at {@code displayPos} by
     * {@code delta} positions (-1 = up, +1 = down). Initialises the
     * pending order from the chain's current effective order on the
     * first move. No-op when the move would push the segment out of
     * range.
     */
    public void moveSegment(ResourceLocation chainId, int totalCount, int displayPos, int delta) {
        if (chainId == null || totalCount <= 0) return;
        int target = displayPos + delta;
        if (displayPos < 0 || displayPos >= totalCount || target < 0 || target >= totalCount) return;
        List<Integer> order = pendingSegmentOrder.get(chainId.toString());
        if (order == null || order.size() != totalCount) {
            order = new ArrayList<>(effectiveSegmentOrder(chainId, totalCount));
            pendingSegmentOrder.put(chainId.toString(), order);
        }
        int tmp = order.get(displayPos);
        order.set(displayPos, order.get(target));
        order.set(target, tmp);
    }

    public Map<String, List<Integer>> snapshotSegmentOrder() {
        Map<String, List<Integer>> out = new LinkedHashMap<>(pendingSegmentOrder.size());
        for (var e : pendingSegmentOrder.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue()));
        return out;
    }

    private void mutateSegment(ResourceLocation chainId, int segIdx,
                               java.util.function.Function<SegmentOverrides.SegmentEdit, SegmentOverrides.SegmentEdit> tx) {
        if (chainId == null) return;
        String key = SegmentOverrides.key(chainId, segIdx);
        SegmentOverrides.SegmentEdit cur = pendingSegmentEdits.getOrDefault(key, SegmentOverrides.SegmentEdit.empty());
        SegmentOverrides.SegmentEdit next = tx.apply(cur);
        if (next == null || next.isNoOp()) {
            pendingSegmentEdits.remove(key);
        } else {
            pendingSegmentEdits.put(key, next);
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    // ────────────────────────────────────────────────────────────
    // Custom selectors (v3)
    // ────────────────────────────────────────────────────────────

    public void addCustomSelector(NameSelector sel) {
        if (sel == null) return;
        pendingCustomSelectors.put(sel.id(), sel);
        pendingRemovedCustomSelectorIds.remove(sel.id());
    }

    public void removeCustomSelector(ResourceLocation id) {
        if (id == null) return;
        pendingCustomSelectors.remove(id);
        pendingRemovedCustomSelectorIds.add(id);
    }

    public Map<ResourceLocation, NameSelector> snapshotCustomSelectors() {
        return new LinkedHashMap<>(pendingCustomSelectors);
    }

    public Set<ResourceLocation> snapshotRemovedCustomSelectorIds() {
        return new LinkedHashSet<>(pendingRemovedCustomSelectorIds);
    }

    public boolean hasPendingCustomSelector(ResourceLocation id) {
        return pendingCustomSelectors.containsKey(id);
    }

    public boolean hasPendingCustomSelectorRemoval(ResourceLocation id) {
        return pendingRemovedCustomSelectorIds.contains(id);
    }

    // ────────────────────────────────────────────────────────────
    // New chains (v3.1)
    // ────────────────────────────────────────────────────────────

    /**
     * Record a brand-new chain the user just created. {@code packId} is
     * the canonical pack id the chain should be written to on save
     * (e.g. {@code mod/adventureitemnames/wholesome}).
     */
    public void addNewChain(ResourceLocation chainId, String packId) {
        if (chainId == null || packId == null) return;
        pendingNewChains.put(chainId, packId);
    }

    public boolean hasPendingNewChain(ResourceLocation chainId) {
        return chainId != null && pendingNewChains.containsKey(chainId);
    }

    public String pendingNewChainPack(ResourceLocation chainId) {
        return chainId == null ? null : pendingNewChains.get(chainId);
    }

    public Map<ResourceLocation, String> snapshotPendingNewChains() {
        return new LinkedHashMap<>(pendingNewChains);
    }

    // ────────────────────────────────────────────────────────────
    // Common
    // ────────────────────────────────────────────────────────────

    public boolean isDirty() {
        return !pendingWeights.isEmpty()
            || !pendingPoolEnabled.isEmpty()
            || !pendingAddedEntries.isEmpty()
            || !pendingRemovedEntries.isEmpty()
            || !pendingChances.isEmpty()
            || !pendingColors.isEmpty()
            || !pendingSelectorTiers.isEmpty()
            || !pendingSelectorEnabled.isEmpty()
            || !pendingSegmentEdits.isEmpty()
            || !pendingSegmentResets.isEmpty()
            || !pendingSegmentOrder.isEmpty()
            || !pendingAppendedSegments.isEmpty()
            || !pendingCustomSelectors.isEmpty()
            || !pendingRemovedCustomSelectorIds.isEmpty()
            || !pendingNewChains.isEmpty();
    }

    public void clear() {
        pendingWeights.clear();
        pendingPoolEnabled.clear();
        pendingAddedEntries.clear();
        pendingRemovedEntries.clear();
        pendingChances.clear();
        pendingColors.clear();
        pendingSelectorTiers.clear();
        pendingSelectorEnabled.clear();
        pendingSegmentEdits.clear();
        pendingSegmentResets.clear();
        pendingSegmentOrder.clear();
        pendingAppendedSegments.clear();
        pendingCustomSelectors.clear();
        pendingRemovedCustomSelectorIds.clear();
        pendingNewChains.clear();
    }
}
