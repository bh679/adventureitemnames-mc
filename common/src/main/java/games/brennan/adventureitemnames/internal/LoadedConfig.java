package games.brennan.adventureitemnames.internal;

/**
 * Bundle of every config layer carried by a single file. Returned by
 * {@link ConfigCodec#parse(com.google.gson.JsonElement, String)} so callers
 * can install all halves atomically into {@code NamingConfig} +
 * {@code NameRegistry}.
 *
 * <p>v3 adds {@code segmentOverrides} (per-segment chain edits) and
 * {@code customSelectors} (user-defined selectors that get injected into
 * the post-datapack-load selector view via
 * {@link NameRegistry#installUserSelectors}).
 */
public record LoadedConfig(
    DisableSet disables,
    WeightOverrides weights,
    ChanceOverrides chances,
    SelectorOverrides selectorOverrides,
    SegmentOverrides segmentOverrides,
    CustomSelectors customSelectors
) {
    public static LoadedConfig empty() {
        return new LoadedConfig(
            new DisableSet(),
            new WeightOverrides(),
            new ChanceOverrides(),
            new SelectorOverrides(),
            new SegmentOverrides(),
            new CustomSelectors());
    }
}
