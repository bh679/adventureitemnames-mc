package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Ordered composition recipe — a chain of {@link NameSegment}s that the
 * {@link NameComposer} walks left-to-right, accumulating output.
 *
 * <p>Chains may reference other chains via {@link NameSegment.WeightedRef},
 * so a top-level {@code weapon_name_full} can repeatedly invoke
 * {@code title_combinations} which in turn invokes {@code title_prefix}
 * and a weighted pick across the category pools.</p>
 *
 * <p>{@code replace} controls how this chain combines with same-id chains
 * from lower-priority packs at load time. When {@code true} (the legacy
 * default), this chain fully replaces lower layers. When {@code false},
 * its segments append their refs to the corresponding lower-layer
 * segments by index — mirroring vanilla's tag-merge convention.</p>
 */
public record NameChain(ResourceLocation id, List<NameSegment> segments, boolean replace) {

    /** Convenience constructor for the legacy full-replacement case. */
    public NameChain(ResourceLocation id, List<NameSegment> segments) {
        this(id, segments, true);
    }
}
