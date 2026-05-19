package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshots the in-memory naming registry, groups pools by source pack,
 * and resolves each pool's contribution (segment index, shipped weight,
 * chains that reference it) so the UI can render the per-datapack table
 * without re-walking the registry every frame.
 *
 * <p>Built fresh each time the user navigates between screens. The cost
 * is small (registry size is tens to low hundreds of entries) and keeps
 * the data view consistent with what's currently loaded.
 */
@Environment(EnvType.CLIENT)
public final class PackGrouping {

    private static final ResourceLocation TITLE_COMBINATIONS =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "title_combinations");

    public record PoolView(
        ResourceLocation poolId,
        String packId,
        int entryCount,
        /** Where this pool sits inside {@code title_combinations}, or {@code null} when not referenced. */
        TitleSlot titleSlot,
        List<ResourceLocation> usedIn
    ) {}

    /** A reference to one pool inside one chain segment, with its shipped weight. */
    public record TitleSlot(ResourceLocation chainId, int segmentIndex, float shippedWeight) {}

    public record PackView(
        String packId,
        List<PoolView> pools,
        int totalEntries,
        float titleCombinationsSum
    ) {}

    private PackGrouping() {}

    /**
     * Human-readable label for a resource-pack id. Vanilla pack ids are
     * stable runtime strings ({@code mod/...}, {@code file/...},
     * {@code vanilla}) but Fabric's dev launcher synthesizes the mod's
     * own resources as {@code generated_<short hash>} — that hash changes
     * every run, so we map any unrecognised "generated_" id to a
     * built-in label.
     */
    public static String friendlyPackName(String packId) {
        if (packId == null || packId.isEmpty()) return "(unknown)";
        return switch (packId) {
            case "vanilla" -> "Vanilla";
            case "mod/adventureitemnames", "fabric" -> "Adventure Item Names";
            case "mod/adventureitemnames/mc_names" -> "Minecraft Pack";
            case "mod/adventureitemnames/wholesome" -> "Wholesome Pack";
            case "mod/adventureitemnames/discord" -> "Discord Supporters Pack";
            case "mod/adventureitemnames/atla" -> "ATLA Pack";
            case "mod/adventureitemnames/adventuretime" -> "Adventure Time Pack";
            default -> {
                String stripped = packId.startsWith("mod/") ? packId.substring(4)
                                : packId.startsWith("file/") ? packId.substring(5)
                                : packId;
                if (stripped.startsWith("generated_")) yield "Adventure Item Names";
                yield stripped;
            }
        };
    }

    /** Group every currently-registered pool by its source pack id. */
    public static Map<String, PackView> snapshot() {
        Map<ResourceLocation, NamePool> pools = NameRegistry.allPools();
        Map<ResourceLocation, NameChain> chains = NameRegistry.allChains();

        Map<ResourceLocation, TitleSlot> titleSlots = indexTitleCombinations(chains);
        Map<ResourceLocation, List<ResourceLocation>> usedIn = indexRefs(chains);

        Map<String, List<PoolView>> grouped = new LinkedHashMap<>();
        for (var e : pools.entrySet()) {
            ResourceLocation id = e.getKey();
            String packId = NameRegistry.packIdOfPool(id);
            PoolView pv = new PoolView(
                id, packId,
                e.getValue().entries().size(),
                titleSlots.get(id),
                usedIn.getOrDefault(id, List.of())
            );
            grouped.computeIfAbsent(packId, k -> new ArrayList<>()).add(pv);
        }

        Map<String, PackView> packs = new LinkedHashMap<>();
        for (var e : grouped.entrySet()) {
            List<PoolView> list = e.getValue();
            int totalEntries = 0;
            float titleSum = 0f;
            for (PoolView pv : list) {
                totalEntries += pv.entryCount();
                if (pv.titleSlot() != null) {
                    titleSum += pv.titleSlot().shippedWeight();
                }
            }
            packs.put(e.getKey(), new PackView(e.getKey(), list, totalEntries, titleSum));
        }
        return packs;
    }

    /** Locate every direct ref from {@code adventureitemnames:title_combinations} to a pool. */
    private static Map<ResourceLocation, TitleSlot> indexTitleCombinations(Map<ResourceLocation, NameChain> chains) {
        Map<ResourceLocation, TitleSlot> out = new LinkedHashMap<>();
        NameChain title = chains.get(TITLE_COMBINATIONS);
        if (title == null) return out;
        int segIdx = 0;
        for (NameSegment seg : title.segments()) {
            for (NameSegment.WeightedRef r : seg.refs()) {
                out.putIfAbsent(r.ref(), new TitleSlot(TITLE_COMBINATIONS, segIdx, r.weight()));
            }
            segIdx++;
        }
        return out;
    }

    /** Reverse map: poolId → chains that reference it. */
    private static Map<ResourceLocation, List<ResourceLocation>> indexRefs(Map<ResourceLocation, NameChain> chains) {
        Map<ResourceLocation, List<ResourceLocation>> out = new LinkedHashMap<>();
        for (NameChain chain : chains.values()) {
            for (NameSegment seg : chain.segments()) {
                for (NameSegment.WeightedRef r : seg.refs()) {
                    out.computeIfAbsent(r.ref(), k -> new ArrayList<>()).add(chain.id());
                }
            }
        }
        return out;
    }

    /**
     * Sum of effective weights for every pool reference in
     * {@code title_combinations} segment 1 (the main pool-picker
     * segment) — used as the normalizer for the live % column. Honors
     * any uncommitted edits in {@code buffer}.
     */
    public static float liveTitleCombinationsTotal(EditBuffer buffer) {
        NameChain title = NameRegistry.chain(TITLE_COMBINATIONS).orElse(null);
        if (title == null) return 0f;
        float total = 0f;
        int segIdx = 0;
        for (NameSegment seg : title.segments()) {
            for (NameSegment.WeightedRef r : seg.refs()) {
                if (!NamingConfig.isPoolEnabled(r.ref()) && !NamingConfig.isChainEnabled(r.ref())) continue;
                Boolean pendingEnabled = buffer.pendingPoolEnabled(r.ref());
                if (Boolean.FALSE.equals(pendingEnabled)) continue;
                total += effectiveWeightWithBuffer(buffer, TITLE_COMBINATIONS, segIdx, r);
            }
            segIdx++;
        }
        return total;
    }

    /** Effective weight that also honors any uncommitted UI edit. */
    public static float effectiveWeightWithBuffer(EditBuffer buffer, ResourceLocation chainId,
                                                  int segIdx, NameSegment.WeightedRef ref) {
        Float pending = buffer.pendingWeight(chainId, segIdx, ref.ref());
        if (pending != null) return Math.max(0f, pending);
        return NamingConfig.effectiveWeight(chainId, segIdx, ref.ref(), ref.weight());
    }
}
