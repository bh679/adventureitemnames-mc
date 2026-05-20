package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a fully-materialised {@link NameChain} (as produced by
 * {@link ChainAssembler}) into per-pack contributions so each pack's
 * {@code naming/chains/<path>.json} can be written separately.
 *
 * <p>Attribution rule: every ref is assigned to its <em>source pack</em>
 * — the pack that ships the pool or chain the ref targets. Context refs
 * (e.g. {@code adventureitemnames:context/item_material}) and refs whose
 * target isn't registered are attributed to the chain's base pack.
 *
 * <p>Output shape: keyed by pack id; the base pack carries the chain's
 * metadata (chance / connection / newline / label / segment count) plus
 * its own refs and is flagged {@code replace: true}. Themed packs carry
 * <em>only</em> their own refs (with a copy of the metadata for sanity —
 * the {@code "replace": false} merger discards it at runtime) and are
 * flagged {@code replace: false}.
 */
public final class PerPackSplitter {

    private PerPackSplitter() {}

    /** Result entry — one pack's chain layer for a given chain id. */
    public record PackLayer(String packId, NameChain chain, boolean replace, boolean empty) {}

    /**
     * Build the per-pack split for {@code effective}. The returned map
     * is keyed by pack id, ordered with the base pack first followed by
     * themed contributors in encounter order.
     */
    public static Map<String, PackLayer> split(NameChain effective) {
        String basePackId = resolveBasePack(effective.id());
        List<NameSegment> segs = effective.segments();

        // Per-segment, ref → source pack id.
        List<Map<String, List<NameSegment.WeightedRef>>> perSegRefsByPack = new ArrayList<>(segs.size());
        Set<String> contributing = new LinkedHashSet<>();
        contributing.add(basePackId);
        for (NameSegment seg : segs) {
            Map<String, List<NameSegment.WeightedRef>> bucket = new LinkedHashMap<>();
            for (NameSegment.WeightedRef ref : seg.refs()) {
                String src = sourcePackOf(ref.ref(), basePackId);
                bucket.computeIfAbsent(src, k -> new ArrayList<>()).add(ref);
                contributing.add(src);
            }
            perSegRefsByPack.add(bucket);
        }

        Map<String, PackLayer> out = new LinkedHashMap<>();
        for (String packId : contributing) {
            List<NameSegment> packSegs = new ArrayList<>(segs.size());
            boolean anyRefs = false;
            for (int i = 0; i < segs.size(); i++) {
                NameSegment baseSeg = segs.get(i);
                List<NameSegment.WeightedRef> packRefs = perSegRefsByPack.get(i).getOrDefault(packId, List.of());
                if (!packRefs.isEmpty()) anyRefs = true;
                packSegs.add(new NameSegment(
                    List.copyOf(packRefs),
                    baseSeg.chance(),
                    baseSeg.connection(),
                    baseSeg.newline(),
                    baseSeg.label()
                ));
            }
            boolean isBase = packId.equals(basePackId);
            // Base pack always considered non-empty (it owns the metadata even
            // if it has zero refs of its own). Themed packs are empty iff they
            // contribute no refs at all.
            boolean empty = !isBase && !anyRefs;
            NameChain layer = new NameChain(effective.id(), List.copyOf(packSegs), isBase);
            out.put(packId, new PackLayer(packId, layer, isBase, empty));
        }
        return out;
    }

    /**
     * Pack id of the chain's "base" layer — the one whose file carries
     * metadata and the {@code "replace": true} flag.
     *
     * <p>For chains in the {@code adventureitemnames} namespace the base
     * is always {@code mod/adventureitemnames} (our own mod resources).
     * We cannot use {@link NameRegistry#packIdOfChain} here because it
     * returns the <em>highest-priority</em> contributor, which is
     * usually a themed pack (Wholesome, Atla, etc.) — treating those as
     * base would write {@code "replace": true} on the themed file and
     * wipe the real base mod's refs on the next reload.
     */
    public static String resolveBasePack(ResourceLocation chainId) {
        if ("adventureitemnames".equals(chainId.getNamespace())) {
            return "mod/adventureitemnames";
        }
        // Chains contributed by other mods or loose datapacks: best-effort
        // fall back to the winning pack id, which is at least guaranteed to
        // be one of the contributors.
        String packId = NameRegistry.packIdOfChain(chainId);
        if (packId == null || NameRegistry.UNKNOWN_PACK.equals(packId)) {
            return "mod/adventureitemnames";
        }
        return packId;
    }

    /**
     * Source pack for {@code refId} — the pack that owns the pool or
     * chain it targets.
     *
     * <p>Pools have a single source (no additive merge) so
     * {@link NameRegistry#packIdOfPool} is authoritative. Chains DO have
     * additive merge across packs so {@link NameRegistry#packIdOfChain}
     * would return whichever pack happens to be highest-priority — which
     * isn't a stable answer. For chain refs we attribute to the chain's
     * own base pack (resolved via the same {@link #resolveBasePack} rule)
     * so the ref always lives next to the chain's primary definition.
     *
     * <p>Context refs and unresolved refs attribute to {@code fallbackBase}.
     */
    public static String sourcePackOf(ResourceLocation refId, String fallbackBase) {
        if (refId.getPath().startsWith("context/")) return fallbackBase;
        if (NameRegistry.pool(refId).isPresent()) {
            String p = PackPaths.canonicalize(NameRegistry.packIdOfPool(refId));
            return NameRegistry.UNKNOWN_PACK.equals(p) ? fallbackBase : p;
        }
        if (NameRegistry.chain(refId).isPresent()) {
            // Chains are merged across packs — attribute the ref to the
            // chain's base pack rather than the (unstable) winning layer.
            return resolveBasePack(refId);
        }
        return fallbackBase;
    }
}
