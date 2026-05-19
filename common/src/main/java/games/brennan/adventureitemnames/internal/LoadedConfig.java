package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameSelector;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bundle of every config layer carried by a single file. Returned by
 * {@link ConfigCodec#parse(com.google.gson.JsonElement, String)} so callers
 * can install every half atomically into {@code NamingConfig}.
 *
 * <p>{@code customSelectors} carries user-defined selectors loaded from the
 * {@code custom_selectors} JSON block — id → {@link NameSelector}. Datapack
 * config files don't populate this (it's strictly a user-layer concept) so
 * the datapack reload path always passes an empty map.
 */
public record LoadedConfig(
    DisableSet disables,
    WeightOverrides weights,
    EntryOverrides entries,
    ChanceOverrides chances,
    SelectorOverrides selectorOverrides,
    Map<ResourceLocation, NameSelector> customSelectors
) {
    public static LoadedConfig empty() {
        return new LoadedConfig(
            new DisableSet(),
            new WeightOverrides(),
            new EntryOverrides(),
            new ChanceOverrides(),
            new SelectorOverrides(),
            new LinkedHashMap<>());
    }
}
