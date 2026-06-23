package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.compat.Ids;
import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NameSegment.WeightedRef;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the additive chain-merge logic used by the chain reload
 * listener. {@code NameRegistry.mergeLayers} is package-private so the
 * test lives in the same package — no reflection needed.
 *
 * <p>These tests don't bootstrap Minecraft; only {@link ResourceLocation}
 * static helpers are touched, which are stateless.</p>
 */
class NameRegistryMergeTest {

    private static final ResourceLocation TITLE = Ids.of(
        "adventureitemnames", "title_combinations");

    private static NameRegistry.ChainLayer layer(NameChain chain, String packId) {
        return new NameRegistry.ChainLayer(chain, packId);
    }

    private static NameSegment seg(List<WeightedRef> refs, float chance, String connection) {
        return new NameSegment(refs, chance, connection, false);
    }

    private static WeightedRef ref(String id, float w) {
        return new WeightedRef(Ids.parse(id), w);
    }

    @Test
    void singleLayer_isReturnedUnchanged() {
        NameSegment s0 = seg(List.of(ref("adventureitemnames:title_prefix", 1.0f)), 1.0f, "");
        NameSegment s1 = seg(List.of(
            ref("adventureitemnames:food", 0.10f),
            ref("adventureitemnames:titles", 0.09f)
        ), 1.0f, " of ");
        NameChain base = new NameChain(TITLE, List.of(s0, s1), true);

        NameChain merged = NameRegistry.mergeLayers(List.of(layer(base, "base")));

        assertEquals(base, merged);
    }

    @Test
    void additiveLayer_appendsRefsToMatchingSegment() {
        NameSegment baseS0 = seg(List.of(ref("adventureitemnames:title_prefix", 1.0f)), 1.0f, "");
        NameSegment baseS1 = seg(List.of(ref("adventureitemnames:food", 0.10f)), 1.0f, " of ");
        NameChain base = new NameChain(TITLE, List.of(baseS0, baseS1), true);

        NameSegment addS0 = seg(List.of(), 1.0f, "");
        NameSegment addS1 = seg(List.of(
            ref("adventureitemnames:mc_biomes", 0.043f),
            ref("adventureitemnames:mc_dimensions", 0.025f)
        ), 1.0f, " of ");
        NameChain mc = new NameChain(TITLE, List.of(addS0, addS1), false);

        NameChain merged = NameRegistry.mergeLayers(List.of(layer(base, "base"), layer(mc, "mc_names")));

        assertEquals(2, merged.segments().size());
        assertEquals(1, merged.segments().get(0).refs().size(), "segment 0 unchanged when add layer empty");
        assertEquals(3, merged.segments().get(1).refs().size(), "segment 1 = base.1 + mc.2");

        // Base refs come first, additive layer's refs follow
        assertEquals("adventureitemnames:food", merged.segments().get(1).refs().get(0).ref().toString());
        assertEquals("adventureitemnames:mc_biomes", merged.segments().get(1).refs().get(1).ref().toString());
        assertEquals("adventureitemnames:mc_dimensions", merged.segments().get(1).refs().get(2).ref().toString());

        // Segment metadata inherited from base
        assertEquals(1.0f, merged.segments().get(1).chance());
        assertEquals(" of ", merged.segments().get(1).connection());
    }

    @Test
    void replaceLayer_overridesEverythingBelow() {
        NameSegment baseSeg = seg(List.of(ref("adventureitemnames:food", 0.10f)), 1.0f, " of ");
        NameChain base = new NameChain(TITLE, List.of(baseSeg), true);

        NameSegment addSeg = seg(List.of(ref("adventureitemnames:mc_biomes", 0.043f)), 1.0f, " of ");
        NameChain additive = new NameChain(TITLE, List.of(addSeg), false);

        NameSegment replaceSeg = seg(List.of(ref("adventureitemnames:custom_only", 0.5f)), 0.5f, ", ");
        NameChain replace = new NameChain(TITLE, List.of(replaceSeg), true);

        NameChain merged = NameRegistry.mergeLayers(List.of(
            layer(base, "base"),
            layer(additive, "mc_names"),
            layer(replace, "third_party")
        ));

        assertEquals(1, merged.segments().get(0).refs().size());
        assertEquals("adventureitemnames:custom_only",
                     merged.segments().get(0).refs().get(0).ref().toString());
        assertEquals(0.5f, merged.segments().get(0).chance());
        assertEquals(", ", merged.segments().get(0).connection());
    }

    @Test
    void multipleAdditiveLayers_concatRefsInOrder() {
        NameSegment baseS0 = seg(List.of(ref("adventureitemnames:title_prefix", 1.0f)), 1.0f, "");
        NameSegment baseS1 = seg(List.of(ref("adventureitemnames:food", 0.10f)), 1.0f, " of ");
        NameChain base = new NameChain(TITLE, List.of(baseS0, baseS1), true);

        NameChain mc = new NameChain(TITLE, List.of(
            seg(List.of(), 1.0f, ""),
            seg(List.of(ref("adventureitemnames:mc_biomes", 0.043f)), 1.0f, " of ")
        ), false);

        NameChain discord = new NameChain(TITLE, List.of(
            seg(List.of(), 1.0f, ""),
            seg(List.of(ref("adventureitemnames:discord_people", 0.06f)), 1.0f, " of ")
        ), false);

        NameChain merged = NameRegistry.mergeLayers(List.of(
            layer(base, "base"),
            layer(mc, "mc_names"),
            layer(discord, "discord")
        ));

        assertEquals(3, merged.segments().get(1).refs().size());
        assertEquals("adventureitemnames:food", merged.segments().get(1).refs().get(0).ref().toString());
        assertEquals("adventureitemnames:mc_biomes", merged.segments().get(1).refs().get(1).ref().toString());
        assertEquals("adventureitemnames:discord_people", merged.segments().get(1).refs().get(2).ref().toString());
    }

    @Test
    void additiveLayer_canAddNewSegmentBeyondBase() {
        NameChain base = new NameChain(TITLE, List.of(
            seg(List.of(ref("adventureitemnames:title_prefix", 1.0f)), 1.0f, "")
        ), true);

        NameSegment extra = seg(List.of(ref("adventureitemnames:adventuretime_titles", 0.1f)), 1.0f, "");
        NameChain adventuretime = new NameChain(TITLE, List.of(
            seg(List.of(), 1.0f, ""),
            extra
        ), false);

        NameChain merged = NameRegistry.mergeLayers(List.of(
            layer(base, "base"),
            layer(adventuretime, "adventuretime")
        ));

        assertEquals(2, merged.segments().size());
        assertEquals(1, merged.segments().get(0).refs().size());
        assertEquals(extra, merged.segments().get(1));
    }
}
