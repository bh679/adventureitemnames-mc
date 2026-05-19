package games.brennan.adventureitemnames.internal;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-selector per-tier chain remapping snapshot for one layer
 * (datapack / user / API). Sibling of {@link WeightOverrides} but for
 * the {@code selectors.tiers} mapping — lets the user point a selector
 * at a different naming chain without editing the shipped JSON.
 *
 * <p>Key shape: {@code selectorId → (tierKey → Optional<chainId>)}.
 * {@code Optional.empty()} represents the {@code (none)} sentinel — the
 * tier is intentionally suppressed even when the selector is enabled.
 * A missing tier key falls through to the next layer / shipped value.
 *
 * <p>Not thread-safe — callers hold the {@code NamingConfig} lock.
 */
public final class SelectorOverrides {

    public final Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> tiers = new HashMap<>();

    public void clear() {
        tiers.clear();
    }

    public void mergeFrom(SelectorOverrides other) {
        if (other == null) return;
        for (var entry : other.tiers.entrySet()) {
            tiers.computeIfAbsent(entry.getKey(), k -> new HashMap<>()).putAll(entry.getValue());
        }
    }

    public boolean isEmpty() {
        return tiers.isEmpty();
    }

    /** Set the override for one tier on one selector. {@code Optional.empty()} = {@code (none)}. */
    public void put(ResourceLocation selectorId, String tierKey, Optional<ResourceLocation> chainId) {
        if (selectorId == null || tierKey == null || chainId == null) return;
        tiers.computeIfAbsent(selectorId, k -> new HashMap<>()).put(tierKey, chainId);
    }

    /** Remove the override for one tier — falls through to the shipped value. */
    public void remove(ResourceLocation selectorId, String tierKey) {
        if (selectorId == null || tierKey == null) return;
        Map<String, Optional<ResourceLocation>> per = tiers.get(selectorId);
        if (per != null) {
            per.remove(tierKey);
            if (per.isEmpty()) tiers.remove(selectorId);
        }
    }

    /** Lookup. Returns {@code null} when no override is set for this tier. */
    public Optional<ResourceLocation> get(ResourceLocation selectorId, String tierKey) {
        if (selectorId == null || tierKey == null) return null;
        Map<String, Optional<ResourceLocation>> per = tiers.get(selectorId);
        if (per == null) return null;
        return per.get(tierKey);
    }

    public Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> snapshot() {
        Map<ResourceLocation, Map<String, Optional<ResourceLocation>>> copy = new LinkedHashMap<>();
        for (var entry : tiers.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }
}
