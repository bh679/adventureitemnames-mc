package games.brennan.adventureitemnames.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Additive override directed at an existing {@link NameChain}. Loaded
 * from {@code data/<ns>/naming/chain_extensions/<name>.json} by the
 * registry, then merged into the target chain at composition-cache
 * rebuild time.
 *
 * <p>Lets multiple packs grow the same chain without overriding it,
 * which would otherwise be a last-write-wins data race. Merge is
 * deterministic: {@link games.brennan.adventureitemnames.internal.ChainMerger}
 * sorts extensions by their full ResourceLocation and applies ops in
 * declaration order.</p>
 *
 * <p>Resolution failures (missing target chain, missing segment) are
 * logged and skipped — one bad extension never blocks the others.</p>
 */
public record NameChainExtension(
    ResourceLocation id,
    ResourceLocation target,
    List<NameChainOp> operations
) {}
