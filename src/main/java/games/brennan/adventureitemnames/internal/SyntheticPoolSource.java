package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NamePool;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Functional interface for runtime-generated pool sources. Implementations
 * derive {@link NamePool} instances from live state (typically vanilla
 * registries) rather than reading JSON files from a pack.
 *
 * <p>Registered with {@link NameRegistry#registerSyntheticPoolSource(String, SyntheticPoolSource)}
 * and invoked at the tail end of the JSON pool reload, after every pack on
 * disk has been read. JSON pools win on id collision — synthetic pools only
 * fill ids that weren't loaded from JSON. This preserves the existing
 * additive/replace semantics: a power user can ship a JSON pool with the same
 * id to fully override the synthetic content.</p>
 *
 * <p>{@link #produce()} runs on the resource-manager apply thread once per
 * {@code /reload}. Implementations should be allocation-light and return an
 * empty map (not null) when they have nothing to contribute.</p>
 *
 * @see VanillaRegistryPoolSource
 */
@FunctionalInterface
public interface SyntheticPoolSource {

    /**
     * Produce the pools this source contributes. Called once per reload.
     * Return an empty map when the source has nothing — never null. Pool ids
     * already present in the JSON-loaded set will be skipped by the caller,
     * so duplicates in the returned map don't cause errors but waste work.
     */
    Map<ResourceLocation, NamePool> produce();

    /**
     * Pairs a {@link SyntheticPoolSource} with the pack id its pools should be
     * tagged with for UI grouping (e.g. {@code "mc_names"}). The pack id only
     * affects {@link NameRegistry#packIdOfPool(ResourceLocation)} lookups —
     * the synthetic pool is available for chain refs regardless of whether
     * the player has the named resource pack enabled.
     */
    record Registration(String packId, SyntheticPoolSource source) {}
}
