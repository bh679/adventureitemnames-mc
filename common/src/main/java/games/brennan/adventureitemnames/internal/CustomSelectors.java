package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable bag of user-defined selectors loaded from the
 * {@code custom_selectors} block of {@code adventureitemnames.json}.
 * Each entry materialises a full {@link NameSelector} record with an item
 * tag + tier-keyed chain id map, identical in shape to a shipped JSON
 * selector.
 *
 * <p>Insertion order is preserved so the configured order in JSON matches
 * the order they appear in the in-game Selectors screen. Not thread-safe;
 * callers hold the {@code NamingConfig} / {@code NameRegistry} lock.
 */
public final class CustomSelectors {

    public final Map<ResourceLocation, NameSelector> selectors = new LinkedHashMap<>();

    public void clear() {
        selectors.clear();
    }

    public void mergeFrom(CustomSelectors other) {
        if (other == null) return;
        selectors.putAll(other.selectors);
    }

    public boolean isEmpty() {
        return selectors.isEmpty();
    }

    public Map<ResourceLocation, NameSelector> snapshot() {
        return new LinkedHashMap<>(selectors);
    }

    public void put(NameSelector sel) {
        if (sel == null) return;
        selectors.put(sel.id(), sel);
    }
}
