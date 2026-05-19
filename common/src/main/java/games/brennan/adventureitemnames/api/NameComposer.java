package games.brennan.adventureitemnames.api;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Walks a {@link NameChain} graph deterministically against a supplied
 * {@link RandomSource} to produce a generated display name, then stamps it
 * onto the {@link ItemStack} as a vanilla
 * {@code DataComponents.CUSTOM_NAME}.
 *
 * <p>Public entrypoint for integrators: call
 * {@link #applyName(ItemStack, RandomSource)} with a seeded random source
 * inside your own loot/spawn pipeline. The mod also ships a Global Loot
 * Modifier that calls this for every vanilla loot table out of the box.</p>
 *
 * <p>Context-aware virtual refs are reserved under the
 * {@code adventureitemnames:context/...} path — they short-circuit
 * pool/chain lookup and read from the {@link ItemStack} instead. Currently:
 * {@link #REF_ITEM_MATERIAL} returns the stack's vanilla material prefix
 * (Iron, Diamond, Netherite, ...). Unknown materials (shield, custom
 * items) return empty so the composing segment gracefully skips.</p>
 */
public final class NameComposer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cap chain recursion depth so a circular ref can't blow the stack. */
    private static final int MAX_DEPTH = 16;

    /** Naming probability for plain (unenchanted) items that match a selector. */
    private static final float CHANCE_PLAIN = 0.30f;
    /** Naming probability for enchanted items that match a selector. */
    private static final float CHANCE_ENCHANTED = 0.50f;

    /** Naming probability for non-aggressive mobs (Animal, WaterAnimal, AmbientCreature, AbstractGolem, Allay). */
    private static final float CHANCE_MOB_PASSIVE = 0.05f;
    /** Naming probability for villagers (AbstractVillager — Villager + WanderingTrader). */
    private static final float CHANCE_MOB_VILLAGER = 1.00f;

    /** Virtual ref resolved from the stack's item id rather than a JSON pool. */
    public static final ResourceLocation REF_ITEM_MATERIAL =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "context/item_material");

    private static final ResourceLocation POOL_TYPE_SYNONYMS =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "type_synonyms");

    private static final ResourceLocation CHAIN_MOB_NAME =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "mob_name");

    private NameComposer() {}

    /**
     * Generate a name for {@code stack} and apply it as
     * {@link DataComponents#CUSTOM_NAME}. No-op when no registered
     * selector covers the stack's item tags or the chosen chain is empty /
     * unresolved.
     */
    public static void applyName(ItemStack stack, RandomSource rng) {
        NameSelector sel = matchAndCheckConfig(stack);
        if (sel == null) return;

        boolean enchanted = stack.isEnchanted();
        if (rng.nextFloat() >= (enchanted ? CHANCE_ENCHANTED : CHANCE_PLAIN)) return;

        composeAndApply(stack, sel, enchanted, rng);
    }

    /**
     * Force a name onto {@code stack} regardless of the natural roll
     * probability — useful for test tooling and integrators that want
     * guaranteed naming without re-implementing the chain walk.
     * Selector and item enable/disable in {@code NamingConfig} are still
     * honoured. No-op when no registered selector covers the stack.
     */
    public static void applyNameAlways(ItemStack stack, RandomSource rng) {
        NameSelector sel = matchAndCheckConfig(stack);
        if (sel == null) return;
        composeAndApply(stack, sel, stack.isEnchanted(), rng);
    }

    private static NameSelector matchAndCheckConfig(ItemStack stack) {
        Optional<NameSelector> maybeSel = NameRegistry.findMatching(stack);
        if (maybeSel.isEmpty()) return null;
        NameSelector sel = maybeSel.get();
        if (!NamingConfig.isSelectorEnabled(sel.id())) return null;
        if (!NamingConfig.isItemEnabled(stack)) return null;
        return sel;
    }

    private static void composeAndApply(ItemStack stack, NameSelector sel, boolean enchanted, RandomSource rng) {
        NameTier tier = enchanted ? NameTier.ENCHANTED : NameTier.PLAIN;
        ResourceLocation chainId = sel.tiers().get(tier.key());
        if (chainId == null) chainId = sel.tiers().get(NameTier.PLAIN.key());
        if (chainId == null) return;

        String name = compose(chainId, stack, sel.appliesTo(), rng, 0);
        if (name == null || name.isBlank()) return;

        name = applyTypeSynonym(name, stack, sel.appliesTo(), rng);

        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    }

    /**
     * Variant of {@link #applyName} that bypasses the per-tier
     * probability gate so a single roll always returns a name (or an
     * empty string if no selector matches / chain resolves empty). Used
     * by the config UI's live preview — see
     * {@code client/PreviewRoller}.
     */
    public static String composePreview(ItemStack stack, boolean enchanted, RandomSource rng) {
        Optional<NameSelector> maybeSel = NameRegistry.findMatching(stack);
        if (maybeSel.isEmpty()) return "";
        NameSelector sel = maybeSel.get();

        NameTier tier = enchanted ? NameTier.ENCHANTED : NameTier.PLAIN;
        ResourceLocation chainId = sel.tiers().get(tier.key());
        if (chainId == null) chainId = sel.tiers().get(NameTier.PLAIN.key());
        if (chainId == null) return "";

        String name = compose(chainId, stack, sel.appliesTo(), rng, 0);
        if (name == null || name.isBlank()) return "";
        return applyTypeSynonym(name, stack, sel.appliesTo(), rng);
    }

    /**
     * Generate a name for a freshly-spawned {@link Mob} and apply it as
     * the vanilla {@code CustomName}. Intended to be called from a mixin
     * on {@code Mob.finalizeSpawn(...)} so it fires exactly once per
     * fresh spawn (never on chunk reload).
     *
     * <p>Probability:
     * <ul>
     *   <li>{@link AbstractVillager} (Villager + WanderingTrader): 100%, name
     *       <em>hover-only</em> (every villager is named — floating plates
     *       would clutter a village).</li>
     *   <li>{@link Animal} / {@link WaterAnimal} / {@link AmbientCreature} /
     *       {@link AbstractGolem} / {@link Allay} that are <em>not</em>
     *       {@link Enemy}: 5%, name <em>always visible</em> (rare → stand out).</li>
     *   <li>All other mobs: no-op.</li>
     * </ul>
     *
     * <p>Pre-existing {@code CustomName} (e.g. from {@code /summon …
     * {CustomName:'"Bessie"'}}) is respected — we early-return.</p>
     */
    public static void applyMobName(Mob mob, RandomSource rng) {
        if (mob.getCustomName() != null) return;

        MobCategory cat = categorize(mob);
        if (cat == null) return;

        if (!NamingConfig.isMobCategoryEnabled(cat)) return;
        if (!NamingConfig.isEntityEnabled(mob.getType())) return;

        float chance;
        boolean nameVisible;
        switch (cat) {
            case VILLAGER -> { chance = CHANCE_MOB_VILLAGER; nameVisible = false; }
            case PASSIVE  -> { chance = CHANCE_MOB_PASSIVE;  nameVisible = true; }
            default -> { return; }
        }

        if (rng.nextFloat() >= chance) return;

        String name = compose(CHAIN_MOB_NAME, ItemStack.EMPTY, null, rng, 0);
        if (name == null || name.isBlank()) return;

        mob.setCustomName(Component.literal(name));
        mob.setCustomNameVisible(nameVisible);
    }

    /**
     * Classify a mob into one of the namable categories, or null when the
     * mob isn't a naming target (hostile, generic, ender dragon, etc.).
     */
    private static MobCategory categorize(Mob mob) {
        if (mob instanceof AbstractVillager) return MobCategory.VILLAGER;
        if ((mob instanceof Animal
              || mob instanceof WaterAnimal
              || mob instanceof AmbientCreature
              || mob instanceof AbstractGolem
              || mob instanceof Allay)
             && !(mob instanceof Enemy)) {
            return MobCategory.PASSIVE;
        }
        return null;
    }

    /**
     * Roll one of four weighted placements for the item-type synonym:
     * none (65%), prefix with space (4%), suffix with space (30%),
     * prefix with " of " (1%). Returns the original name unchanged when
     * the synonym pool has no entry tagged for this item kind.
     */
    private static String applyTypeSynonym(String name, ItemStack stack,
                                           ResourceLocation targetTagId, RandomSource rng) {
        if (!NamingConfig.isPoolEnabled(POOL_TYPE_SYNONYMS)) return name;
        Optional<NamePool> pool = NameRegistry.pool(POOL_TYPE_SYNONYMS);
        if (pool.isEmpty()) return name;
        String synonym = pickPoolEntry(pool.get(), targetTagId, rng);
        if (synonym.isEmpty()) return name;
        int roll = rng.nextInt(100);
        if (roll < 65) return name;
        if (roll < 69) return synonym + " " + name;
        if (roll < 99) return name + " " + synonym;
        return synonym + " of " + name;
    }

    /**
     * Vanilla material prefix mapping. Order matters — longer prefixes
     * (e.g. {@code golden_}) must match before any shorter substring
     * collisions. Returns empty string for items whose path doesn't carry
     * a material prefix (shields, custom mod items, vanilla items without
     * a tier — e.g. carrot, written_book).
     */
    public static String materialOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (rl == null) return "";
        String path = rl.getPath();
        if (path.startsWith("netherite_")) return "Netherite";
        if (path.startsWith("chainmail_")) return "Chainmail";
        if (path.startsWith("diamond_"))   return "Diamond";
        if (path.startsWith("leather_"))   return "Leather";
        if (path.startsWith("golden_"))    return "Gold";
        if (path.startsWith("turtle_"))    return "Turtle";
        if (path.startsWith("iron_"))      return "Iron";
        if (path.startsWith("stone_"))     return "Stone";
        if (path.startsWith("wooden_"))    return "Wood";
        return "";
    }

    private static String compose(ResourceLocation chainId, ItemStack stack,
                                  ResourceLocation targetTagId, RandomSource rng, int depth) {
        if (depth >= MAX_DEPTH) {
            LOGGER.warn("[AdventureItemNames] max compose depth reached at chain {}", chainId);
            return "";
        }
        Optional<NameChain> maybeChain = NameRegistry.chain(chainId);
        if (maybeChain.isEmpty()) {
            LOGGER.warn("[AdventureItemNames] chain '{}' not registered", chainId);
            return "";
        }
        StringBuilder out = new StringBuilder();
        int segIdx = 0;
        for (NameSegment seg : maybeChain.get().segments()) {
            if (seg.chance() < 1f && rng.nextFloat() >= seg.chance()) { segIdx++; continue; }

            NameSegment.WeightedRef picked = pickWeighted(chainId, segIdx, seg.refs(), rng);
            if (picked == null) { segIdx++; continue; }

            String fragment = resolveRef(picked.ref(), stack, targetTagId, rng, depth + 1);
            if (fragment == null || fragment.isEmpty()) { segIdx++; continue; }

            if (out.length() > 0) {
                out.append(seg.connection());
                if (seg.newline()) out.append('\n');
            }
            out.append(fragment);
            segIdx++;
        }
        return out.toString();
    }

    private static String resolveRef(ResourceLocation refId, ItemStack stack,
                                     ResourceLocation targetTagId, RandomSource rng, int depth) {
        if (refId.equals(REF_ITEM_MATERIAL)) {
            return materialOf(stack);
        }
        Optional<NameChain> chain = NameRegistry.chain(refId);
        if (chain.isPresent()) {
            if (!NamingConfig.isChainEnabled(refId)) return "";
            return compose(refId, stack, targetTagId, rng, depth);
        }
        Optional<NamePool> pool = NameRegistry.pool(refId);
        if (pool.isPresent()) {
            if (!NamingConfig.isPoolEnabled(refId)) return "";
            return pickPoolEntry(pool.get(), targetTagId, rng);
        }
        LOGGER.warn("[AdventureItemNames] ref '{}' resolves to neither pool nor chain", refId);
        return "";
    }

    private static String pickPoolEntry(NamePool pool, ResourceLocation targetTagId, RandomSource rng) {
        List<NamePool.PoolEntry> compatible = new ArrayList<>(pool.entries().size());
        for (NamePool.PoolEntry e : pool.entries()) {
            if (e.itemTypes().isEmpty() || e.itemTypes().contains(targetTagId)) {
                compatible.add(e);
            }
        }
        if (compatible.isEmpty()) return "";
        return compatible.get(rng.nextInt(compatible.size())).text();
    }

    /**
     * Cumulative-weight pick over a list of weighted refs, consulting
     * {@link NamingConfig#effectiveWeight} per ref so user / API
     * overrides take precedence over the shipped weight. Returns the
     * last ref when float drift carries past the total — never returns
     * null for a non-empty input that has any non-zero effective weight.
     */
    private static NameSegment.WeightedRef pickWeighted(ResourceLocation chainId, int segIdx,
                                                        List<NameSegment.WeightedRef> refs, RandomSource rng) {
        if (refs.isEmpty()) return null;
        float total = 0f;
        for (NameSegment.WeightedRef r : refs) {
            total += NamingConfig.effectiveWeight(chainId, segIdx, r.ref(), r.weight());
        }
        if (total <= 0f) return null;
        float roll = rng.nextFloat() * total;
        float cum = 0f;
        for (NameSegment.WeightedRef r : refs) {
            cum += NamingConfig.effectiveWeight(chainId, segIdx, r.ref(), r.weight());
            if (roll < cum) return r;
        }
        return refs.get(refs.size() - 1);
    }
}
