package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a fixed batch of sample names through {@link NameComposer},
 * temporarily promoting the {@link EditBuffer}'s pending edits to the
 * API layer so the user sees what their uncommitted changes would
 * produce. The promotion is wrapped in try/finally — the API layer is
 * snapshot-restored even if a roll throws.
 *
 * <p>Each roll alternates plain / enchanted tier and rotates through
 * representative sword / pickaxe / axe / armor / shield stacks so the
 * preview covers what a typical loot chest would actually contain.
 *
 * <p>Pending pool disable / enable toggles also apply: a pool the user
 * has just toggled off appears suppressed in the preview.
 */
@Environment(EnvType.CLIENT)
public final class PreviewRoller {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ItemStack[] SAMPLES = new ItemStack[] {
        new ItemStack(Items.IRON_SWORD),
        new ItemStack(Items.DIAMOND_PICKAXE),
        new ItemStack(Items.NETHERITE_AXE),
        new ItemStack(Items.IRON_HELMET),
        new ItemStack(Items.DIAMOND_CHESTPLATE),
        new ItemStack(Items.NETHERITE_BOOTS),
        new ItemStack(Items.SHIELD),
    };

    private PreviewRoller() {}

    /** Roll {@code count} sample names. Returns plaintext strings. */
    public static List<String> roll(int count, EditBuffer buffer, ResourceLocation forcePoolForSegment1) {
        NamingConfig.ApiSnapshot snap = NamingConfig.snapshotApiLayer();
        try {
            applyBufferToApi(buffer, forcePoolForSegment1);
            return doRolls(count);
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] preview roll failed: {}", ex.getMessage());
            return List.of();
        } finally {
            NamingConfig.restoreApiLayer(snap);
        }
    }

    private static void applyBufferToApi(EditBuffer buffer, ResourceLocation forcePoolForSegment1) {
        for (var entry : buffer.snapshotWeights().entrySet()) {
            KeyParts kp = parseKey(entry.getKey());
            if (kp != null) {
                NamingConfig.overrideWeight(kp.chainId, kp.segIdx, kp.refId, entry.getValue());
            }
        }
        for (var poolId : buffer.snapshotDisabledPools()) {
            NamingConfig.disablePool(poolId);
        }
        for (var poolId : buffer.snapshotEnabledPools()) {
            NamingConfig.enablePool(poolId);
        }

        if (forcePoolForSegment1 != null) {
            ResourceLocation chain = ResourceLocation.fromNamespaceAndPath(
                "adventureitemnames", "title_combinations");
            var ch = NameRegistry.chain(chain).orElse(null);
            if (ch != null && ch.segments().size() > 1) {
                for (var r : ch.segments().get(1).refs()) {
                    float w = r.ref().equals(forcePoolForSegment1) ? Math.max(0.01f, r.weight()) : 0f;
                    NamingConfig.overrideWeight(chain, 1, r.ref(), w);
                }
            }
        }
    }

    private static List<String> doRolls(int count) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource rng = mc.level != null ? mc.level.random : RandomSource.create();
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStack stack = SAMPLES[i % SAMPLES.length].copy();
            boolean enchanted = (i % 2 == 0);
            String name = NameComposer.composePreview(stack, enchanted, rng);
            out.add(name.isEmpty() ? "—" : name);
        }
        return out;
    }

    private record KeyParts(ResourceLocation chainId, int segIdx, ResourceLocation refId) {}

    private static KeyParts parseKey(String key) {
        int a = key.indexOf('#');
        if (a <= 0) return null;
        int b = key.indexOf('#', a + 1);
        if (b <= a + 1 || b == key.length() - 1) return null;
        ResourceLocation chainId = ResourceLocation.tryParse(key.substring(0, a));
        ResourceLocation refId = ResourceLocation.tryParse(key.substring(b + 1));
        if (chainId == null || refId == null) return null;
        try {
            return new KeyParts(chainId, Integer.parseInt(key.substring(a + 1, b)), refId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
