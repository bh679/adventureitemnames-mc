package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameChainExtension;
import games.brennan.adventureitemnames.api.NameChainOp;
import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure function — applies a sorted, deterministic merge of
 * {@link NameChainExtension}s onto the raw chain map. Result is a new
 * map; inputs are never mutated.
 *
 * <p>Extensions are sorted by full {@link ResourceLocation} before being
 * applied. Within an extension, ops execute in declared order against the
 * chain's current working state, so {@code add_segment} insertion
 * indices are evaluated against the post-prior-op state.</p>
 *
 * <p>Missing targets, missing segments, and unknown op types are logged
 * and skipped — one bad input never aborts the merge.</p>
 */
public final class ChainMerger {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ChainMerger() {}

    public static Map<ResourceLocation, NameChain> merge(
        Map<ResourceLocation, NameChain> rawChains,
        Map<ResourceLocation, NameChainExtension> extensions
    ) {
        Map<ResourceLocation, List<NameSegment>> working = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, NameChain> e : rawChains.entrySet()) {
            working.put(e.getKey(), new ArrayList<>(e.getValue().segments()));
        }

        List<NameChainExtension> sorted = new ArrayList<>(extensions.values());
        sorted.sort(Comparator.comparing(ext -> ext.id().toString()));

        for (NameChainExtension ext : sorted) {
            List<NameSegment> chain = working.get(ext.target());
            if (chain == null) {
                LOGGER.warn("[AdventureItemNames] extension {} targets unknown chain {} — skipped",
                    ext.id(), ext.target());
                continue;
            }
            for (NameChainOp op : ext.operations()) {
                applyOp(ext.id(), ext.target(), chain, op);
            }
        }

        Map<ResourceLocation, NameChain> out = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<NameSegment>> e : working.entrySet()) {
            out.put(e.getKey(), new NameChain(e.getKey(), List.copyOf(e.getValue())));
        }
        return out;
    }

    private static void applyOp(ResourceLocation extId, ResourceLocation chainId,
                                List<NameSegment> chain, NameChainOp op) {
        if (op instanceof NameChainOp.AddRefs add) {
            int idx = resolveSegmentIndex(chain, add.target());
            if (idx < 0) {
                LOGGER.warn("[AdventureItemNames] extension {} add_refs target {} not found in chain {} — skipped",
                    extId, describeRef(add.target()), chainId);
                return;
            }
            NameSegment current = chain.get(idx);
            List<NameSegment.WeightedRef> merged = new ArrayList<>(current.refs());
            merged.addAll(add.refs());
            chain.set(idx, new NameSegment(
                current.name(),
                List.copyOf(merged),
                current.chance(),
                current.connection(),
                current.newline()
            ));
        } else if (op instanceof NameChainOp.AddSegment add) {
            int insertAt = resolveInsertIndex(chain, add.at());
            chain.add(insertAt, add.segment());
        }
    }

    private static int resolveSegmentIndex(List<NameSegment> chain, NameChainOp.SegmentRef ref) {
        if (ref instanceof NameChainOp.SegmentRef.ByName byName) {
            for (int i = 0; i < chain.size(); i++) {
                if (byName.name().equals(chain.get(i).name())) return i;
            }
            return -1;
        }
        if (ref instanceof NameChainOp.SegmentRef.ByIndex byIndex) {
            int i = byIndex.index();
            return (i >= 0 && i < chain.size()) ? i : -1;
        }
        return -1;
    }

    private static int resolveInsertIndex(List<NameSegment> chain, NameChainOp.InsertPos pos) {
        if (pos instanceof NameChainOp.InsertPos.Start) return 0;
        if (pos instanceof NameChainOp.InsertPos.End) return chain.size();
        if (pos instanceof NameChainOp.InsertPos.At at) {
            int i = at.index();
            if (i < 0) return 0;
            return Math.min(i, chain.size());
        }
        return chain.size();
    }

    private static String describeRef(NameChainOp.SegmentRef ref) {
        if (ref instanceof NameChainOp.SegmentRef.ByName byName) return "name=" + byName.name();
        if (ref instanceof NameChainOp.SegmentRef.ByIndex byIndex) return "index=" + byIndex.index();
        return "?";
    }
}
