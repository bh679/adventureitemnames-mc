package games.brennan.adventureitemnames.internal;

/**
 * Bundle of every config layer carried by a single file. Returned by
 * {@link ConfigCodec#parse(com.google.gson.JsonElement, String)} so callers
 * can install every half atomically into {@code NamingConfig}.
 */
public record LoadedConfig(
    DisableSet disables,
    WeightOverrides weights,
    EntryOverrides entries,
    ChanceOverrides chances,
    SelectorOverrides selectorOverrides
) {
    public static LoadedConfig empty() {
        return new LoadedConfig(
            new DisableSet(),
            new WeightOverrides(),
            new EntryOverrides(),
            new ChanceOverrides(),
            new SelectorOverrides());
    }
}
