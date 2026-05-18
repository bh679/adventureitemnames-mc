package games.brennan.adventureitemnames.api;

import java.util.Locale;

/**
 * Coarse mob grouping used by {@link NameComposer#applyMobName} and by the
 * enable/disable config. Only the two categories that are namable today
 * are exposed — additional values will be added as the composer learns to
 * name new mob kinds.
 */
public enum MobCategory {
    VILLAGER,
    PASSIVE;

    /** Lowercase key used in config JSON files. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a config key (case-insensitive). Returns null for unknown keys. */
    public static MobCategory fromKey(String key) {
        if (key == null) return null;
        String norm = key.toLowerCase(Locale.ROOT).trim();
        for (MobCategory c : values()) {
            if (c.key().equals(norm)) return c;
        }
        return null;
    }
}
