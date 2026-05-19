package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * Sample stacks reachable via the preview panel's click-to-cycle.
     * Order: the six primary slots (weapon / two tools / three armor
     * pieces) come first so the default mapping is one-to-one; the four
     * "extras" (shovel / bow / leggings / shield) appear after, so
     * clicking past boots cycles through them and wraps back to sword.
     * The bow slot is the live preview for {@code adventureitemnames:bow}
     * (covers vanilla bow + crossbow via the {@code adventureitemnames:bows}
     * item tag).
     */
    public static final ItemStack[] DEFAULT_SAMPLES = new ItemStack[] {
        new ItemStack(Items.IRON_SWORD),
        new ItemStack(Items.DIAMOND_PICKAXE),
        new ItemStack(Items.NETHERITE_AXE),
        new ItemStack(Items.IRON_HELMET),
        new ItemStack(Items.DIAMOND_CHESTPLATE),
        new ItemStack(Items.NETHERITE_BOOTS),
        new ItemStack(Items.IRON_SHOVEL),
        new ItemStack(Items.BOW),
        new ItemStack(Items.IRON_LEGGINGS),
        new ItemStack(Items.SHIELD),
    };

    private PreviewRoller() {}

    /** One roll's worth of preview state — the icon to draw + the rolled name. */
    public record Result(ItemStack icon, String name) {}

    /** Bulk roll for every supplied stack, applying buffer overrides once across the batch. */
    public static List<Result> rollBatch(List<ItemStack> stacks, List<Boolean> enchanted,
                                         EditBuffer buffer, ResourceLocation forcePoolForSegment1,
                                         boolean gateByChance) {
        NamingConfig.ApiSnapshot snap = NamingConfig.snapshotApiLayer();
        try {
            applyBufferToApi(buffer, forcePoolForSegment1);
            return doBatchRolls(stacks, enchanted, gateByChance);
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] preview batch roll failed: {}", ex.getMessage());
            return Collections.emptyList();
        } finally {
            NamingConfig.restoreApiLayer(snap);
        }
    }

    /** Roll one stack with the buffer applied. Used for click-to-cycle on a single slot. */
    public static Result rollSingle(ItemStack stack, boolean enchanted,
                                    EditBuffer buffer, ResourceLocation forcePoolForSegment1,
                                    boolean gateByChance) {
        NamingConfig.ApiSnapshot snap = NamingConfig.snapshotApiLayer();
        try {
            applyBufferToApi(buffer, forcePoolForSegment1);
            return doSingleRoll(stack, enchanted, gateByChance);
        } catch (Exception ex) {
            LOGGER.warn("[AdventureItemNames] preview single roll failed: {}", ex.getMessage());
            return new Result(stack, "—");
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
        for (var entry : buffer.snapshotChances().entrySet()) {
            NamingConfig.overrideChance(entry.getKey(), entry.getValue());
        }
        for (var selectorEntry : buffer.snapshotSelectorTiers().entrySet()) {
            for (var tierEntry : selectorEntry.getValue().entrySet()) {
                NamingConfig.overrideSelectorTier(
                    selectorEntry.getKey(), tierEntry.getKey(), tierEntry.getValue());
            }
        }
        for (var selectorId : buffer.snapshotDisabledSelectors()) {
            NamingConfig.disableSelector(selectorId);
        }
        for (var selectorId : buffer.snapshotEnabledSelectors()) {
            NamingConfig.enableSelector(selectorId);
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

    private static List<Result> doBatchRolls(List<ItemStack> stacks, List<Boolean> enchanted, boolean gateByChance) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource rng = mc.level != null ? mc.level.random : RandomSource.create();
        List<Result> out = new ArrayList<>(stacks.size());
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i).copy();
            boolean ench = enchanted.get(i);
            out.add(rollOne(stack, ench, rng, gateByChance));
        }
        return out;
    }

    private static Result doSingleRoll(ItemStack stack, boolean enchanted, boolean gateByChance) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource rng = mc.level != null ? mc.level.random : RandomSource.create();
        return rollOne(stack.copy(), enchanted, rng, gateByChance);
    }

    private static Result rollOne(ItemStack stack, boolean enchanted, RandomSource rng, boolean gateByChance) {
        if (gateByChance) {
            float chance = enchanted ? NamingConfig.chanceFor(ChanceKind.ENCHANTED)
                                     : NamingConfig.chanceFor(ChanceKind.PLAIN);
            if (rng.nextFloat() >= chance) return new Result(stack, "—");
        }
        String name = NameComposer.composePreview(stack, enchanted, rng);
        if (name.isEmpty()) logEmptyPreview(stack);
        return new Result(stack, name.isEmpty() ? "—" : name);
    }

    /**
     * Diagnostic: surface which item the preview couldn't name and
     * whether the failure was at selector match or downstream compose.
     * The most common cause on first-render is the client-side tag
     * registry not yet being synced from the integrated server when
     * the screen mounts — distinguished here from "matched but
     * produced empty name" so it's obvious which path failed.
     */
    private static void logEmptyPreview(ItemStack stack) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (NameRegistry.findMatching(stack).isEmpty()) {
            LOGGER.info("[AdventureItemNames] preview: no selector matches '{}' "
                + "(tag registry may not be synced yet — try Reroll or /reload)", itemId);
        } else {
            LOGGER.info("[AdventureItemNames] preview: selector matched '{}' but chain composed empty", itemId);
        }
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
