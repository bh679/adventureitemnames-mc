package games.brennan.adventureitemnames.api;

import games.brennan.adventureitemnames.internal.ChanceOverrides;
import games.brennan.adventureitemnames.internal.DisableSet;
import games.brennan.adventureitemnames.internal.EntryOverrides;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import games.brennan.adventureitemnames.internal.SelectorOverrides;
import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Public configuration surface for enabling/disabling parts of the naming
 * system. Three layers stack monotonically — any source disables a thing
 * → it's disabled:
 *
 * <ul>
 *   <li><b>Datapack layer</b> — JSON files under {@code data/<modid>/disabled/}.
 *       Multiple datapacks contribute different files; all union.</li>
 *   <li><b>User layer</b> — {@code <configDir>/adventureitemnames.json}.
 *       Loaded at startup and re-read on every {@code /reload}.</li>
 *   <li><b>API layer</b> — runtime mutation via the {@code disableXxx} /
 *       {@code enableXxx} methods on this class.</li>
 * </ul>
 *
 * <p>{@code enableXxx} only clears the API-layer entry for the given id;
 * datapack and user-config disables remain in effect. Counter-allowlists
 * in JSON files are deliberately not supported — to override a
 * lower-layer disable, ship a higher-priority datapack or call the API.
 *
 * <p>JSON layers accept {@code namespace:*} wildcards in any id list.
 * {@code adventureitemnames:context/*} virtual refs (e.g. {@code item_material})
 * are never affected by disable rules — they short-circuit ref resolution.
 *
 * <p>Disable is silent and graceful: a disabled pool returns the empty
 * string, a chain segment whose refs are all disabled contributes
 * nothing, a disabled selector skips loot naming, a disabled mob category
 * skips spawn naming. No exceptions are thrown.
 *
 * <p><b>Note:</b> {@code isItemEnabled(ItemStack)} can only subtract from
 * coverage. To <em>add</em> naming to an item class that no selector
 * matches today, ship a selector JSON — there is no allowlist counterpart.
 */
public final class NamingConfig {

    private static final Object LOCK = new Object();
    private static final DisableSet DATAPACK = new DisableSet();
    private static final DisableSet USER = new DisableSet();
    private static final DisableSet API = new DisableSet();

    private static final WeightOverrides USER_WEIGHTS = new WeightOverrides();
    private static final WeightOverrides API_WEIGHTS = new WeightOverrides();

    private static final EntryOverrides USER_ENTRIES = new EntryOverrides();
    private static final EntryOverrides API_ENTRIES = new EntryOverrides();

    private static final ChanceOverrides USER_CHANCES = new ChanceOverrides();
    private static final ChanceOverrides API_CHANCES = new ChanceOverrides();

    private static final SelectorOverrides USER_SELECTORS = new SelectorOverrides();
    private static final SelectorOverrides API_SELECTORS = new SelectorOverrides();

    private static final SegmentOverrides USER_SEGMENTS = new SegmentOverrides();
    private static final SegmentOverrides API_SEGMENTS = new SegmentOverrides();

    private NamingConfig() {}

    // ─────────────────────────────────────────────────────────────
    // Internal layer setters (called from ConfigListener / UserConfigLoader)
    // ─────────────────────────────────────────────────────────────

    /** Replace the datapack-layer disable set. Internal — called by the reload listener. */
    public static void setDatapackLayer(DisableSet newLayer) {
        synchronized (LOCK) {
            DATAPACK.clear();
            if (newLayer != null) DATAPACK.mergeFrom(newLayer);
        }
    }

    /** Replace the user-config-layer disable set. Internal — called by the user-config loader. */
    public static void setUserLayer(DisableSet newLayer) {
        synchronized (LOCK) {
            USER.clear();
            if (newLayer != null) USER.mergeFrom(newLayer);
        }
    }

    /** Replace the user-config-layer weight overrides. Internal — called by the user-config loader. */
    public static void setUserWeightOverrides(WeightOverrides newOverrides) {
        synchronized (LOCK) {
            USER_WEIGHTS.clear();
            if (newOverrides != null) USER_WEIGHTS.mergeFrom(newOverrides);
        }
    }

    /** Replace the user-config-layer pool entry overrides. Internal — called by the user-config loader. */
    public static void setUserEntryOverrides(EntryOverrides newOverrides) {
        synchronized (LOCK) {
            USER_ENTRIES.clear();
            if (newOverrides != null) USER_ENTRIES.mergeFrom(newOverrides);
        }
    }

    /** Replace the user-config-layer chance overrides. Internal — called by the user-config loader. */
    public static void setUserChances(ChanceOverrides newOverrides) {
        synchronized (LOCK) {
            USER_CHANCES.clear();
            if (newOverrides != null) USER_CHANCES.mergeFrom(newOverrides);
        }
    }

    /** Replace the user-config-layer selector tier overrides. Internal — called by the user-config loader. */
    public static void setUserSelectorOverrides(SelectorOverrides newOverrides) {
        synchronized (LOCK) {
            USER_SELECTORS.clear();
            if (newOverrides != null) USER_SELECTORS.mergeFrom(newOverrides);
        }
    }

    /** Replace the user-config-layer per-segment overrides. Internal — called by the user-config loader. */
    public static void setUserSegmentOverrides(SegmentOverrides newOverrides) {
        synchronized (LOCK) {
            USER_SEGMENTS.clear();
            if (newOverrides != null) USER_SEGMENTS.mergeFrom(newOverrides);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // API-layer setters
    // ─────────────────────────────────────────────────────────────

    public static void disablePool(ResourceLocation id)     { synchronized (LOCK) { API.pools.add(id); } }
    public static void enablePool(ResourceLocation id)      { synchronized (LOCK) { API.pools.remove(id); } }
    public static void disableChain(ResourceLocation id)    { synchronized (LOCK) { API.chains.add(id); } }
    public static void enableChain(ResourceLocation id)     { synchronized (LOCK) { API.chains.remove(id); } }
    public static void disableSelector(ResourceLocation id) { synchronized (LOCK) { API.selectors.add(id); } }
    public static void enableSelector(ResourceLocation id)  { synchronized (LOCK) { API.selectors.remove(id); } }

    public static void disableItem(ResourceLocation itemId) { synchronized (LOCK) { API.itemIds.add(itemId); } }
    public static void enableItem(ResourceLocation itemId)  { synchronized (LOCK) { API.itemIds.remove(itemId); } }
    public static void disableItemTag(TagKey<Item> tag) {
        if (tag != null) synchronized (LOCK) { API.itemTags.add(tag.location()); }
    }
    public static void enableItemTag(TagKey<Item> tag) {
        if (tag != null) synchronized (LOCK) { API.itemTags.remove(tag.location()); }
    }

    public static void disableMobCategory(MobCategory cat) {
        if (cat != null) synchronized (LOCK) { API.mobCategories.add(cat); }
    }
    public static void enableMobCategory(MobCategory cat) {
        if (cat != null) synchronized (LOCK) { API.mobCategories.remove(cat); }
    }

    public static void disableEntity(ResourceLocation entityId) { synchronized (LOCK) { API.entityIds.add(entityId); } }
    public static void enableEntity(ResourceLocation entityId)  { synchronized (LOCK) { API.entityIds.remove(entityId); } }
    public static void disableEntityTag(TagKey<EntityType<?>> tag) {
        if (tag != null) synchronized (LOCK) { API.entityTags.add(tag.location()); }
    }
    public static void enableEntityTag(TagKey<EntityType<?>> tag) {
        if (tag != null) synchronized (LOCK) { API.entityTags.remove(tag.location()); }
    }

    /**
     * Set an API-layer weight override on one weighted ref of one chain
     * segment. Takes precedence over user-config overrides. Setting
     * {@code weight} to a negative value clears the API override and lets
     * the user-config / shipped value take effect.
     */
    public static void overrideWeight(ResourceLocation chainId, int segmentIndex,
                                      ResourceLocation refId, float weight) {
        if (chainId == null || refId == null) return;
        String key = WeightOverrides.key(chainId, segmentIndex, refId);
        synchronized (LOCK) {
            if (weight < 0f) API_WEIGHTS.weights.remove(key);
            else API_WEIGHTS.weights.put(key, weight);
        }
    }

    /** Clear an API-layer weight override — falls through to user / shipped value. */
    public static void clearWeightOverride(ResourceLocation chainId, int segmentIndex,
                                           ResourceLocation refId) {
        if (chainId == null || refId == null) return;
        String key = WeightOverrides.key(chainId, segmentIndex, refId);
        synchronized (LOCK) { API_WEIGHTS.weights.remove(key); }
    }

    /**
     * Add a pool entry at the API layer. Takes effect immediately for
     * {@link NameComposer} rolls until cleared via
     * {@link #clearEntryOverride(ResourceLocation)} or
     * {@link #restoreApiLayer(ApiSnapshot)}. Used by the in-game UI
     * preview to surface unsaved edits.
     */
    public static void addEntry(ResourceLocation poolId, NamePool.PoolEntry entry) {
        if (poolId == null || entry == null) return;
        synchronized (LOCK) { API_ENTRIES.addEntry(poolId, entry); }
    }

    /** Remove a pool entry by text at the API layer. Exact-text equality. */
    public static void removeEntry(ResourceLocation poolId, String text) {
        if (poolId == null || text == null) return;
        synchronized (LOCK) { API_ENTRIES.removeText(poolId, text); }
    }

    /** Drop every API-layer entry override for one pool. */
    public static void clearEntryOverride(ResourceLocation poolId) {
        if (poolId == null) return;
        synchronized (LOCK) { API_ENTRIES.clearForPool(poolId); }
    }

    /**
     * Set an API-layer chance override. Value is clamped to {@code [0, 1]};
     * a negative value clears the override and lets the user-config /
     * default take effect.
     */
    public static void overrideChance(ChanceKind kind, float value) {
        if (kind == null) return;
        synchronized (LOCK) {
            if (value < 0f) API_CHANCES.values.remove(kind);
            else API_CHANCES.values.put(kind, Math.min(1f, value));
        }
    }

    public static void clearChanceOverride(ChanceKind kind) {
        if (kind == null) return;
        synchronized (LOCK) { API_CHANCES.values.remove(kind); }
    }

    /**
     * Set an API-layer selector tier override. {@code Optional.empty()}
     * suppresses naming for that tier even when the selector is enabled.
     */
    public static void overrideSelectorTier(ResourceLocation selectorId, String tierKey,
                                            Optional<ResourceLocation> chainId) {
        if (selectorId == null || tierKey == null || chainId == null) return;
        synchronized (LOCK) { API_SELECTORS.put(selectorId, tierKey, chainId); }
    }

    public static void clearSelectorTierOverride(ResourceLocation selectorId, String tierKey) {
        if (selectorId == null || tierKey == null) return;
        synchronized (LOCK) { API_SELECTORS.remove(selectorId, tierKey); }
    }

    /**
     * Set an API-layer override for one chain segment. Any null field on
     * {@code edit} leaves that aspect of the segment untouched and falls
     * through to the lower layers. Passing a {@code SegmentEdit.isNoOp()}
     * value clears the API-layer entry entirely.
     */
    public static void overrideSegment(ResourceLocation chainId, int segmentIndex,
                                       SegmentOverrides.SegmentEdit edit) {
        if (chainId == null) return;
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            if (edit == null || edit.isNoOp()) {
                API_SEGMENTS.edits.remove(key);
            } else {
                API_SEGMENTS.edits.put(key, edit);
            }
        }
    }

    public static void clearSegmentOverride(ResourceLocation chainId, int segmentIndex) {
        if (chainId == null) return;
        synchronized (LOCK) { API_SEGMENTS.edits.remove(SegmentOverrides.key(chainId, segmentIndex)); }
    }

    // ─────────────────────────────────────────────────────────────
    // API-layer snapshot/restore — preview roller uses these to apply
    // pending UI edits non-destructively. Not for general consumption.
    // ─────────────────────────────────────────────────────────────

    /** Internal snapshot of the API layer. Restore via {@link #restoreApiLayer}. */
    public record ApiSnapshot(
        DisableSet disables,
        WeightOverrides weights,
        EntryOverrides entries,
        ChanceOverrides chances,
        SelectorOverrides selectorTiers,
        SegmentOverrides segments
    ) {}

    public static ApiSnapshot snapshotApiLayer() {
        synchronized (LOCK) {
            DisableSet d = new DisableSet();
            d.mergeFrom(API);
            WeightOverrides w = new WeightOverrides();
            w.mergeFrom(API_WEIGHTS);
            EntryOverrides e = API_ENTRIES.snapshot();
            ChanceOverrides c = new ChanceOverrides();
            c.mergeFrom(API_CHANCES);
            SelectorOverrides s = new SelectorOverrides();
            s.mergeFrom(API_SELECTORS);
            SegmentOverrides g = new SegmentOverrides();
            g.mergeFrom(API_SEGMENTS);
            return new ApiSnapshot(d, w, e, c, s, g);
        }
    }

    public static void restoreApiLayer(ApiSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (LOCK) {
            API.clear();
            API.mergeFrom(snapshot.disables());
            API_WEIGHTS.clear();
            API_WEIGHTS.mergeFrom(snapshot.weights());
            API_ENTRIES.clear();
            API_ENTRIES.mergeFrom(snapshot.entries());
            API_CHANCES.clear();
            API_CHANCES.mergeFrom(snapshot.chances());
            API_SELECTORS.clear();
            API_SELECTORS.mergeFrom(snapshot.selectorTiers());
            API_SEGMENTS.clear();
            API_SEGMENTS.mergeFrom(snapshot.segments());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────

    public static boolean isPoolEnabled(ResourceLocation id) {
        if (id == null) return true;
        synchronized (LOCK) {
            return !DATAPACK.pools.contains(id) && !USER.pools.contains(id) && !API.pools.contains(id);
        }
    }

    public static boolean isChainEnabled(ResourceLocation id) {
        if (id == null) return true;
        synchronized (LOCK) {
            return !DATAPACK.chains.contains(id) && !USER.chains.contains(id) && !API.chains.contains(id);
        }
    }

    public static boolean isSelectorEnabled(ResourceLocation id) {
        if (id == null) return true;
        synchronized (LOCK) {
            return !DATAPACK.selectors.contains(id) && !USER.selectors.contains(id) && !API.selectors.contains(id);
        }
    }

    /**
     * Per-item gate checked in addition to the selector gate. Returns
     * false when the stack's item id is in any layer's item-id disable
     * list, or when any of the stack's item tags is in any layer's
     * item-tag disable list. Tags are resolved lazily here so the tag
     * system doesn't have to be ready at config-load time.
     */
    public static boolean isItemEnabled(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        synchronized (LOCK) {
            if (itemId != null
                && (DATAPACK.itemIds.contains(itemId)
                 || USER.itemIds.contains(itemId)
                 || API.itemIds.contains(itemId))) {
                return false;
            }
            boolean[] disabledByTag = { false };
            stack.getTags().forEach(tagKey -> {
                if (disabledByTag[0]) return;
                ResourceLocation tagId = tagKey.location();
                if (DATAPACK.itemTags.contains(tagId)
                 || USER.itemTags.contains(tagId)
                 || API.itemTags.contains(tagId)) {
                    disabledByTag[0] = true;
                }
            });
            return !disabledByTag[0];
        }
    }

    /**
     * Effective weight for one weighted ref inside one chain segment,
     * with precedence API → user-config → shipped. Returns the
     * {@code shippedWeight} unchanged when no layer carries an override.
     *
     * <p>Negative override values are clamped to {@code 0f} (treated as
     * a "suppress this ref" marker). The shipped value is returned as-is
     * even if it's negative — that's an upstream JSON issue, not for this
     * method to second-guess.
     */
    public static float effectiveWeight(ResourceLocation chainId, int segmentIndex,
                                        ResourceLocation refId, float shippedWeight) {
        if (chainId == null || refId == null) return shippedWeight;
        String key = WeightOverrides.key(chainId, segmentIndex, refId);
        synchronized (LOCK) {
            Float api = API_WEIGHTS.weights.get(key);
            if (api != null) return Math.max(0f, api);
            Float user = USER_WEIGHTS.weights.get(key);
            if (user != null) return Math.max(0f, user);
        }
        return shippedWeight;
    }

    /**
     * Read-only snapshot of every active user-layer weight override.
     * Used by the config UI to show which weights have been edited.
     */
    public static Map<String, Float> snapshotUserWeightOverrides() {
        synchronized (LOCK) { return USER_WEIGHTS.snapshot(); }
    }

    /**
     * Effective entry list for one pool, after USER and API entry
     * overrides are applied (removes from shipped, then adds appended).
     *
     * <p>Hot path: when neither USER nor API has any edit for this
     * pool's id, returns {@code pool.entries()} directly with no copy —
     * the common case stays allocation-free. Otherwise builds a fresh
     * list each call. No per-pool cache in v1; add one if profiling
     * shows the slow path is ever hot.
     *
     * <p>The {@link NameComposer} consults this in {@code pickPoolEntry}
     * instead of reading {@code pool.entries()} directly, so user edits
     * affect every roll.
     */
    public static List<NamePool.PoolEntry> effectivePoolEntries(NamePool pool) {
        if (pool == null) return List.of();
        ResourceLocation id = pool.id();
        synchronized (LOCK) {
            if (USER_ENTRIES.isEmptyFor(id) && API_ENTRIES.isEmptyFor(id)) {
                return pool.entries();
            }
            Set<String> removed = new LinkedHashSet<>();
            Set<String> u = USER_ENTRIES.removed.get(id);
            if (u != null) removed.addAll(u);
            Set<String> a = API_ENTRIES.removed.get(id);
            if (a != null) removed.addAll(a);

            List<NamePool.PoolEntry> out = new ArrayList<>(pool.entries().size());
            for (NamePool.PoolEntry e : pool.entries()) {
                if (!removed.contains(e.text())) out.add(e);
            }
            // Added entries are also filtered by the removed-set so that
            // "remove and re-add" is order-independent and a stale USER
            // added entry can be retired by a subsequent `removed` line.
            List<NamePool.PoolEntry> userAdded = USER_ENTRIES.added.get(id);
            if (userAdded != null) {
                for (NamePool.PoolEntry e : userAdded) {
                    if (!removed.contains(e.text())) out.add(e);
                }
            }
            List<NamePool.PoolEntry> apiAdded = API_ENTRIES.added.get(id);
            if (apiAdded != null) {
                for (NamePool.PoolEntry e : apiAdded) {
                    if (!removed.contains(e.text())) out.add(e);
                }
            }
            return out;
        }
    }

    /**
     * Effective probability for one {@link ChanceKind} gate. Precedence
     * API → user → {@link ChanceKind#defaultValue()}. Returned value is
     * already clamped to {@code [0, 1]}.
     */
    public static float chanceFor(ChanceKind kind) {
        if (kind == null) return 0f;
        synchronized (LOCK) {
            Float api = API_CHANCES.values.get(kind);
            if (api != null) return clamp01(api);
            Float user = USER_CHANCES.values.get(kind);
            if (user != null) return clamp01(user);
        }
        return kind.defaultValue();
    }

    public static float chancePlain()       { return chanceFor(ChanceKind.PLAIN); }
    public static float chanceEnchanted()   { return chanceFor(ChanceKind.ENCHANTED); }
    public static float chanceMobPassive()  { return chanceFor(ChanceKind.MOB_PASSIVE); }
    public static float chanceMobVillager() { return chanceFor(ChanceKind.MOB_VILLAGER); }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** Read-only snapshot of user-layer chance overrides. UI uses this to seed edit widgets. */
    public static Map<ChanceKind, Float> snapshotUserChances() {
        synchronized (LOCK) { return USER_CHANCES.snapshot(); }
    }

    /**
     * Effective chain id for {@code selectorId}'s {@code tierKey}, with
     * precedence API → user → shipped (the {@code shippedChainId}). A
     * non-null override with {@code Optional.empty()} represents the
     * {@code (none)} sentinel — naming is suppressed for that tier even
     * if the selector is enabled. A null {@code shippedChainId} signals
     * the shipped JSON didn't define this tier (legitimate for selectors
     * that only specify plain).
     */
    public static Optional<ResourceLocation> effectiveTierChain(ResourceLocation selectorId,
                                                                String tierKey,
                                                                ResourceLocation shippedChainId) {
        if (selectorId == null || tierKey == null) {
            return Optional.ofNullable(shippedChainId);
        }
        synchronized (LOCK) {
            Optional<ResourceLocation> api = API_SELECTORS.get(selectorId, tierKey);
            if (api != null) return api;
            Optional<ResourceLocation> user = USER_SELECTORS.get(selectorId, tierKey);
            if (user != null) return user;
        }
        return Optional.ofNullable(shippedChainId);
    }

    /** Read-only snapshot of user-layer selector tier overrides. */
    public static Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> snapshotUserSelectorOverrides() {
        synchronized (LOCK) { return USER_SELECTORS.snapshot(); }
    }

    /**
     * Effective per-segment chance with precedence API → user → shipped.
     * Result is clamped to {@code [0, 1]}.
     */
    public static float effectiveSegmentChance(ResourceLocation chainId, int segmentIndex, float shipped) {
        if (chainId == null) return shipped;
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            SegmentOverrides.SegmentEdit api = API_SEGMENTS.edits.get(key);
            if (api != null && api.chance() != null) return clamp01(api.chance());
            SegmentOverrides.SegmentEdit user = USER_SEGMENTS.edits.get(key);
            if (user != null && user.chance() != null) return clamp01(user.chance());
        }
        return shipped;
    }

    /**
     * Effective per-segment connection string with precedence
     * API → user → shipped. Empty string is a legitimate override
     * (suppresses the separator) — only {@code null} falls through.
     */
    public static String effectiveSegmentConnection(ResourceLocation chainId, int segmentIndex, String shipped) {
        if (chainId == null) return shipped;
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            SegmentOverrides.SegmentEdit api = API_SEGMENTS.edits.get(key);
            if (api != null && api.connection() != null) return api.connection();
            SegmentOverrides.SegmentEdit user = USER_SEGMENTS.edits.get(key);
            if (user != null && user.connection() != null) return user.connection();
        }
        return shipped;
    }

    /** Effective per-segment newline flag with precedence API → user → shipped. */
    public static boolean effectiveSegmentNewline(ResourceLocation chainId, int segmentIndex, boolean shipped) {
        if (chainId == null) return shipped;
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            SegmentOverrides.SegmentEdit api = API_SEGMENTS.edits.get(key);
            if (api != null && api.newline() != null) return api.newline();
            SegmentOverrides.SegmentEdit user = USER_SEGMENTS.edits.get(key);
            if (user != null && user.newline() != null) return user.newline();
        }
        return shipped;
    }

    /**
     * Effective per-segment ref list. When any layer carries a non-null
     * refs override it <em>replaces</em> the shipped list wholesale; per-ref
     * weight overrides are still applied on top via
     * {@link #effectiveWeight}. Returned list is unmodifiable.
     */
    public static List<NameSegment.WeightedRef> effectiveSegmentRefs(ResourceLocation chainId, int segmentIndex,
                                                                     List<NameSegment.WeightedRef> shipped) {
        if (chainId == null) return shipped;
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            SegmentOverrides.SegmentEdit api = API_SEGMENTS.edits.get(key);
            if (api != null && api.refs() != null) return List.copyOf(api.refs());
            SegmentOverrides.SegmentEdit user = USER_SEGMENTS.edits.get(key);
            if (user != null && user.refs() != null) return List.copyOf(user.refs());
        }
        return shipped;
    }

    /** Read-only snapshot of user-layer per-segment overrides. */
    public static Map<String, SegmentOverrides.SegmentEdit> snapshotUserSegmentOverrides() {
        synchronized (LOCK) { return USER_SEGMENTS.snapshot(); }
    }

    /**
     * User's preferred display label for a segment. Returns an empty
     * string when no override is set; the UI falls back to a synthetic
     * {@code "Seg N"} when this is empty.
     */
    public static String effectiveSegmentLabel(ResourceLocation chainId, int segmentIndex) {
        if (chainId == null) return "";
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            SegmentOverrides.SegmentEdit api = API_SEGMENTS.edits.get(key);
            if (api != null && api.label() != null) return api.label();
            SegmentOverrides.SegmentEdit user = USER_SEGMENTS.edits.get(key);
            if (user != null && user.label() != null) return user.label();
        }
        return "";
    }

    /** Read-only snapshot of user-layer appended-segments map. */
    public static Map<String, List<NameSegment>> snapshotUserAppendedSegments() {
        synchronized (LOCK) { return USER_SEGMENTS.snapshotAppended(); }
    }

    /**
     * Effective segment count for {@code chainId}: shipped count plus any
     * segments appended via user-config or API overrides (segments
     * marked {@code removed} are NOT subtracted — the composer still
     * needs to visit those indices to apply field overrides; it just
     * skips the body).
     */
    public static int effectiveSegmentCount(ResourceLocation chainId, int shippedCount) {
        if (chainId == null) return shippedCount;
        synchronized (LOCK) {
            return shippedCount + API_SEGMENTS.appendedCount(chainId) + USER_SEGMENTS.appendedCount(chainId);
        }
    }

    /**
     * Resolve the {@link NameSegment} record at {@code segmentIndex} in
     * the effective segment list. For {@code 0 <= idx < shipped.size()}
     * the shipped record is returned. Indices past the shipped count are
     * looked up in the appended lists — user-config first, then API
     * (matching the additive-only nature of appended segments).
     * Returns {@code null} for out-of-range indices.
     */
    public static NameSegment effectiveSegmentAt(ResourceLocation chainId, int segmentIndex,
                                                  List<NameSegment> shipped) {
        if (chainId == null || segmentIndex < 0) return null;
        if (segmentIndex < shipped.size()) return shipped.get(segmentIndex);
        int rel = segmentIndex - shipped.size();
        synchronized (LOCK) {
            int userCount = USER_SEGMENTS.appendedCount(chainId);
            if (rel < userCount) return USER_SEGMENTS.appendedAt(chainId, rel);
            return API_SEGMENTS.appendedAt(chainId, rel - userCount);
        }
    }

    /**
     * Effective display/composer order for {@code chainId}. Returns a list
     * of "original" segment indices that the composer should visit in
     * sequence — the identity range {@code [0, totalCount)} when no
     * reorder override is set, otherwise a permutation of that range from
     * the highest-priority layer that carries one (API → user).
     *
     * <p>Length-mismatch fallback: if the stored order doesn't match
     * {@code totalCount} (e.g. user appended a segment after saving a
     * reorder), the identity range is returned to keep the composer
     * sane. The UI handles append-after-reorder by editing the order
     * array itself.
     */
    public static List<Integer> effectiveSegmentOrder(ResourceLocation chainId, int totalCount) {
        if (chainId == null || totalCount <= 0) return identityRange(totalCount);
        synchronized (LOCK) {
            List<Integer> api = API_SEGMENTS.orderOf(chainId);
            if (api != null && api.size() == totalCount) return List.copyOf(api);
            List<Integer> user = USER_SEGMENTS.orderOf(chainId);
            if (user != null && user.size() == totalCount) return List.copyOf(user);
        }
        return identityRange(totalCount);
    }

    private static List<Integer> identityRange(int n) {
        if (n <= 0) return List.of();
        Integer[] arr = new Integer[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        return List.of(arr);
    }

    /** True when any layer has marked this segment as removed (composer will skip its body). */
    public static boolean isSegmentRemoved(ResourceLocation chainId, int segmentIndex) {
        if (chainId == null) return false;
        String key = SegmentOverrides.key(chainId, segmentIndex);
        synchronized (LOCK) {
            SegmentOverrides.SegmentEdit api = API_SEGMENTS.edits.get(key);
            if (api != null && Boolean.TRUE.equals(api.removed())) return true;
            SegmentOverrides.SegmentEdit user = USER_SEGMENTS.edits.get(key);
            return user != null && Boolean.TRUE.equals(user.removed());
        }
    }

    /**
     * True when any layer carries an explicit override (including
     * {@code (none)}) for this selector's tier. Used by the composer to
     * decide whether to honour a {@code (none)} suppression vs fall back
     * to the {@code PLAIN} tier when {@code ENCHANTED} is unset.
     */
    public static boolean hasTierOverride(ResourceLocation selectorId, String tierKey) {
        if (selectorId == null || tierKey == null) return false;
        synchronized (LOCK) {
            return API_SELECTORS.get(selectorId, tierKey) != null
                || USER_SELECTORS.get(selectorId, tierKey) != null;
        }
    }

    public static boolean isMobCategoryEnabled(MobCategory cat) {
        if (cat == null) return false;
        synchronized (LOCK) {
            return !DATAPACK.mobCategories.contains(cat)
                && !USER.mobCategories.contains(cat)
                && !API.mobCategories.contains(cat);
        }
    }

    /**
     * Per-entity gate checked in addition to the mob-category gate.
     * Returns false when the entity type id is in any layer's entity-id
     * disable list, or any of its type tags is in any entity-tag list.
     */
    public static boolean isEntityEnabled(EntityType<?> type) {
        if (type == null) return false;
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        synchronized (LOCK) {
            if (entityId != null
                && (DATAPACK.entityIds.contains(entityId)
                 || USER.entityIds.contains(entityId)
                 || API.entityIds.contains(entityId))) {
                return false;
            }
            boolean[] disabledByTag = { false };
            type.builtInRegistryHolder().tags().forEach(tagKey -> {
                if (disabledByTag[0]) return;
                ResourceLocation tagId = tagKey.location();
                if (DATAPACK.entityTags.contains(tagId)
                 || USER.entityTags.contains(tagId)
                 || API.entityTags.contains(tagId)) {
                    disabledByTag[0] = true;
                }
            });
            return !disabledByTag[0];
        }
    }
}
