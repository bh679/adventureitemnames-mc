package games.brennan.adventureitemnames.api;

/**
 * The four top-level naming probability gates. Each value is the
 * probability that a given trigger event fires a naming roll. Defaults
 * match the previous hard-coded constants in {@link NameComposer} prior
 * to the v2 config refactor.
 *
 * <p>JSON key under {@code config/adventureitemnames.json} → {@code chances}
 * is given by {@link #key()}. {@link #defaultValue()} is the fallback
 * applied when no datapack / user / API layer carries an override.
 */
public enum ChanceKind {

    /** Plain (unenchanted) item matches a selector. */
    PLAIN("plain", 0.30f),
    /** Enchanted item matches a selector. */
    ENCHANTED("enchanted", 0.50f),
    /** Plain (unenchanted) item description (lore) roll. Independent of name chance. */
    DESCRIPTION_PLAIN("description_plain", 0.30f),
    /** Enchanted item description (lore) roll. Independent of name chance. */
    DESCRIPTION_ENCHANTED("description_enchanted", 0.50f),
    /** Passive mob (Animal / WaterAnimal / AmbientCreature / Allay / AbstractGolem) spawns. */
    MOB_PASSIVE("mob_passive", 0.05f),
    /** Villager / wandering trader spawns. */
    MOB_VILLAGER("mob_villager", 1.00f),
    /** Player takes a freshly-crafted item from a crafting result slot — gates the appended description (lore). */
    CRAFTED_DESCRIPTION("crafted_description", 1.00f);

    private final String key;
    private final float defaultValue;

    ChanceKind(String key, float defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() { return key; }
    public float defaultValue() { return defaultValue; }

    /** Lookup by JSON key, returns {@code null} when unknown. */
    public static ChanceKind fromKey(String key) {
        if (key == null) return null;
        for (ChanceKind k : values()) {
            if (k.key.equals(key)) return k;
        }
        return null;
    }
}
