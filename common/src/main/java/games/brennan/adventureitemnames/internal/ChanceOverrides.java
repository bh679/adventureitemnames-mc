package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.ChanceKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mutable per-{@link ChanceKind} probability snapshot for one layer
 * (datapack / user / API). Missing keys fall through to the next layer
 * and ultimately {@link ChanceKind#defaultValue()}.
 *
 * <p>Values are not clamped here — the storing layer is responsible for
 * keeping each entry in {@code [0.0, 1.0]}. Not thread-safe; callers
 * hold the {@code NamingConfig} lock.
 */
public final class ChanceOverrides {

    public final Map<ChanceKind, Float> values = new EnumMap<>(ChanceKind.class);

    public void clear() {
        values.clear();
    }

    public void mergeFrom(ChanceOverrides other) {
        if (other != null) values.putAll(other.values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<ChanceKind, Float> snapshot() {
        return new EnumMap<>(values);
    }
}
