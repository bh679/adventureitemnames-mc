package games.brennan.adventureitemnames.internal;

/**
 * Bundle of every config layer carried by a single file. Returned by
 * {@link ConfigCodec#parse(com.google.gson.JsonElement, String)} so callers
 * can install both halves atomically into {@code NamingConfig}.
 */
public record LoadedConfig(DisableSet disables, WeightOverrides weights) {
    public static LoadedConfig empty() {
        return new LoadedConfig(new DisableSet(), new WeightOverrides());
    }
}
