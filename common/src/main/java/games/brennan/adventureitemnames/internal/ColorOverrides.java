package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.ChanceKind;
import net.minecraft.ChatFormatting;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mutable per-{@link ChanceKind} {@link ChatFormatting} snapshot for one
 * layer (currently only the user-config layer). Missing keys fall through
 * to the next layer and ultimately to {@code null} — i.e. no color
 * styling is applied, vanilla default styling stands.
 *
 * <p>Mirrors {@link ChanceOverrides} structurally so the load/save/edit
 * pipeline can treat colors and chances uniformly. Not thread-safe;
 * callers hold the {@code NamingConfig} lock.
 */
public final class ColorOverrides {

    public final Map<ChanceKind, ChatFormatting> values = new EnumMap<>(ChanceKind.class);

    public void clear() {
        values.clear();
    }

    public void mergeFrom(ColorOverrides other) {
        if (other != null) values.putAll(other.values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<ChanceKind, ChatFormatting> snapshot() {
        return new EnumMap<>(values);
    }
}
