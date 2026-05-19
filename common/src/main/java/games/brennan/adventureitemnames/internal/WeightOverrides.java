package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable per-segment per-ref weight override snapshot for one layer
 * (user-config / API). Sibling of {@link DisableSet}, layered on the same
 * three-layer model in {@code NamingConfig} but with precedence semantics
 * rather than monotonic-union semantics.
 *
 * <p>Keys are stable string identifiers in the form
 * {@code <chain_id>#<segment_index>#<ref_id>}. The {@link NameSegment} list
 * is 0-indexed.
 *
 * <p>A stored value of {@code 0f} suppresses that ref in that segment; a
 * missing key falls through to the next layer (and ultimately the shipped
 * weight on the chain JSON). Not thread-safe — callers hold the
 * {@code NamingConfig} lock.
 */
public final class WeightOverrides {

    public final Map<String, Float> weights = new HashMap<>();

    public static String key(ResourceLocation chainId, int segmentIndex, ResourceLocation refId) {
        return chainId + "#" + segmentIndex + "#" + refId;
    }

    public void clear() {
        weights.clear();
    }

    public void mergeFrom(WeightOverrides other) {
        weights.putAll(other.weights);
    }

    public boolean isEmpty() {
        return weights.isEmpty();
    }

    public Map<String, Float> snapshot() {
        return new LinkedHashMap<>(weights);
    }
}
