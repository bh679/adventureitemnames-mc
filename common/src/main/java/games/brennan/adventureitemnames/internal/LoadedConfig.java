package games.brennan.adventureitemnames.internal;

/**
 * Bundle of every config layer carried by a single file. Returned by
 * {@link ConfigCodec#parse(com.google.gson.JsonElement, String)} so callers
 * can install every layer atomically into {@code NamingConfig} +
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
    EntryOverrides entries,
    ChanceOverrides chances,
    ColorOverrides colors,
    SelectorOverrides selectorOverrides,
    SegmentOverrides segmentOverrides,
    CustomSelectors customSelectors
) {
    public static LoadedConfig empty() {
        return new LoadedConfig(
            new DisableSet(),
            new WeightOverrides(),
            new EntryOverrides(),
            new ChanceOverrides(),
            new ColorOverrides(),
            new SelectorOverrides(),
            new SegmentOverrides(),
            new CustomSelectors());
    }
}
