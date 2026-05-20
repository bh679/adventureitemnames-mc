package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the post-edit {@link NameChain} record for a chain id by
 * folding the {@code segment_overrides} / {@code appended_segments} /
 * {@code segment_order} layers from {@link NamingConfig}'s user layer
 * onto the chain's current shipped record. Used by the dev-mode save
 * flow to compute the JSON that should land on disk after a user
 * commits their edits.
 *
 * <p>This is a <em>materialisation</em> pass — it bakes every
 * override (chance / connection / newline / refs / label / removed
 * flag, plus appended segments and segment order) into a flat
 * {@link NameChain} record so the rest of the save pipeline doesn't
 * have to reason about layered overrides.
 *
 * <p>Returns {@code null} when the chain isn't registered.
 */
public final class ChainAssembler {

    private ChainAssembler() {}

    /**
     * Build the effective {@link NameChain} record for {@code chainId}
     * by applying every active user-layer override. Equivalent to "what
     * does the composer see at runtime" with the user layer baked in.
     */
    public static NameChain assembleEffective(ResourceLocation chainId) {
        var maybeShipped = NameRegistry.chain(chainId);
        if (maybeShipped.isEmpty()) return null;
        NameChain shipped = maybeShipped.get();
        List<NameSegment> shippedSegs = shipped.segments();
        int totalCount = NamingConfig.effectiveSegmentCount(chainId, shippedSegs.size());
        List<Integer> order = NamingConfig.effectiveSegmentOrder(chainId, totalCount);

        List<NameSegment> out = new ArrayList<>(order.size());
        for (int origIdx : order) {
            if (NamingConfig.isSegmentRemoved(chainId, origIdx)) continue;
            NameSegment base = NamingConfig.effectiveSegmentAt(chainId, origIdx, shippedSegs);
            if (base == null) continue;

            float chance = NamingConfig.effectiveSegmentChance(chainId, origIdx, base.chance());
            String connection = NamingConfig.effectiveSegmentConnection(chainId, origIdx, base.connection());
            boolean newline = NamingConfig.effectiveSegmentNewline(chainId, origIdx, base.newline());
            List<NameSegment.WeightedRef> refs = NamingConfig.effectiveSegmentRefs(chainId, origIdx, base.refs());
            String label = NamingConfig.effectiveSegmentLabel(chainId, origIdx, base.label());

            out.add(new NameSegment(List.copyOf(refs), chance, connection, newline, label));
        }
        return new NameChain(shipped.id(), List.copyOf(out), true);
    }
}
