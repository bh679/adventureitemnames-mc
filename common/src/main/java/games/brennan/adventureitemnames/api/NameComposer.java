package games.brennan.adventureitemnames.api;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Virtual ref resolved from the stack's item id rather than a JSON pool. */
    public static final ResourceLocation REF_ITEM_MATERIAL =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "context/item_material");

    private static final ResourceLocation POOL_TYPE_SYNONYMS =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "type_synonyms");

    private static final ResourceLocation CHAIN_MOB_NAME =
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "mob_name");

    /** Pool ids that already triggered the user-blanked-pool fallback warning. */
    private static final Set<ResourceLocation> FALLBACK_WARNED = ConcurrentHashMap.newKeySet();

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
        float chance = enchanted ? NamingConfig.chanceEnchanted() : NamingConfig.chancePlain();
        if (rng.nextFloat() >= chance) return;

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
        applyComposedName(stack, sel, tier, rng);
        applyComposedDescription(stack, sel, tier, rng);
    }

    private static void applyComposedName(ItemStack stack, NameSelector sel, NameTier tier, RandomSource rng) {
        ResourceLocation chainId = resolveTierChain(sel, tier).orElse(null);
        if (chainId == null) return;

        String name = compose(chainId, stack, sel.appliesTo(), rng, 0);
        if (name == null || name.isBlank()) return;

        name = applyTypeSynonym(name, stack, sel.appliesTo(), rng);

        ChanceKind colorKind = tier == NameTier.ENCHANTED ? ChanceKind.ENCHANTED : ChanceKind.PLAIN;
        stack.set(DataComponents.CUSTOM_NAME, withColor(Component.literal(name), colorKind));
    }

    /**
     * Resolve and apply the per-tier description chain to the stack as
     * appended {@code DataComponents.LORE} lines. Description tiers are
     * optional — a selector with an empty description map produces no
     * lore changes. PLAIN fallback for ENCHANTED mirrors name-tier
     * resolution: explicit {@code (none)} on ENCHANTED suppresses, missing
     * ENCHANTED key falls through to PLAIN.
     *
     * <p>Description-tier resolution intentionally bypasses the
     * {@code NamingConfig} override layer used by name-tier resolution —
     * config-side overrides for description tiers are an editor-UI
     * feature deferred to a follow-up.
     */
    private static void applyComposedDescription(ItemStack stack, NameSelector sel, NameTier tier, RandomSource rng) {
        ResourceLocation descChainId = resolveDescriptionTierChain(sel, tier).orElse(null);
        if (descChainId == null) return;

        ChanceKind kind = tier == NameTier.ENCHANTED
            ? ChanceKind.DESCRIPTION_ENCHANTED
            : ChanceKind.DESCRIPTION_PLAIN;
        float chance = NamingConfig.chanceFor(kind);
        if (rng.nextFloat() >= chance) return;

        String desc = compose(descChainId, stack, sel.appliesTo(), rng, 0);
        if (desc == null || desc.isBlank()) return;

        appendLore(stack, desc, kind);
    }

    /**
     * Description-tier counterpart of {@link #resolveTierChain}. Looks up
     * the {@code description_<tier>} key in the selector override layer
     * (lets the UI re-point a description chain without editing the
     * shipped JSON), falling back to the selector's shipped
     * {@code description_tiers} map and then the PLAIN tier when ENCHANTED
     * isn't explicitly bound.
     */
    private static Optional<ResourceLocation> resolveDescriptionTierChain(NameSelector sel, NameTier tier) {
        String descKey = DESCRIPTION_TIER_PREFIX + tier.key();
        Map<String, ResourceLocation> shippedDescTiers = sel.descriptionTiers();
        ResourceLocation shipped = shippedDescTiers.get(tier.key());
        Optional<ResourceLocation> chain = NamingConfig.effectiveTierChain(sel.id(), descKey, shipped);
        if (chain.isPresent()) return chain;
        if (NamingConfig.hasTierOverride(sel.id(), descKey)) return chain;
        if (tier == NameTier.PLAIN) return chain;
        String plainDescKey = DESCRIPTION_TIER_PREFIX + NameTier.PLAIN.key();
        return NamingConfig.effectiveTierChain(sel.id(), plainDescKey,
            shippedDescTiers.get(NameTier.PLAIN.key()));
    }

    /**
     * Tier-key prefix for description-tier overrides stored in the same
     * {@link games.brennan.adventureitemnames.internal.SelectorOverrides}
     * map as name-tier overrides. Keeps the override layer single, while
     * letting {@link games.brennan.adventureitemnames.internal.PackSelectorWriter}
     * route these keys to the JSON's {@code description_tiers} block.
     */
    public static final String DESCRIPTION_TIER_PREFIX = "description_";

    /**
     * Walk overrides + shipped JSON to find the name chain id for one
     * tier on one selector. ENCHANTED falls back to PLAIN only when no
     * explicit override is set — an explicit {@code (none)} override on
     * ENCHANTED suppresses naming for enchanted items without spilling
     * into PLAIN.
     */
    private static Optional<ResourceLocation> resolveTierChain(NameSelector sel, NameTier tier) {
        Optional<ResourceLocation> chain = NamingConfig.effectiveTierChain(
            sel.id(), tier.key(), sel.tiers().get(tier.key()));
        if (chain.isPresent()) return chain;
        if (NamingConfig.hasTierOverride(sel.id(), tier.key())) return chain;
        if (tier == NameTier.PLAIN) return chain;
        return NamingConfig.effectiveTierChain(
            sel.id(), NameTier.PLAIN.key(), sel.tiers().get(NameTier.PLAIN.key()));
    }

    /**
     * Resolve a tier-chain id from a shipped tier map without consulting
     * the {@code NamingConfig} override layer. ENCHANTED falls back to
     * PLAIN when the ENCHANTED key is absent.
     */
    private static ResourceLocation resolveShippedTierChain(Map<String, ResourceLocation> tierMap, NameTier tier) {
        ResourceLocation chainId = tierMap.get(tier.key());
        if (chainId != null) return chainId;
        if (tier == NameTier.PLAIN) return null;
        return tierMap.get(NameTier.PLAIN.key());
    }

    /**
     * Split {@code text} on {@code \n} and append the resulting lines to
     * the stack's existing {@code DataComponents.LORE} component (or an
     * empty lore if the stack has none). Each line takes its color from
     * {@link NamingConfig#colorFor(ChanceKind) colorFor(colorKind)};
     * vanilla's default lore styling (dark purple italic) applies on top
     * when no color override is set. Clamped to {@link ItemLore#MAX_LINES}
     * so a misconfigured chain can't blow the vanilla constructor's size
     * check.
     */
    private static void appendLore(ItemStack stack, String text, ChanceKind colorKind) {
        ItemLore existing = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> merged = new ArrayList<>(existing.lines());
        for (String line : text.split("\n", -1)) {
            if (merged.size() >= ItemLore.MAX_LINES) break;
            merged.add(withColor(Component.literal(line), colorKind));
        }
        stack.set(DataComponents.LORE, new ItemLore(List.copyOf(merged)));
    }

    /**
     * Wrap {@code component} in the configured color for {@code colorKind}.
     * Returns the original component unchanged when no color override is
     * set so vanilla default styling stays untouched.
     */
    private static Component withColor(MutableComponent component, ChanceKind colorKind) {
        Optional<ChatFormatting> color = NamingConfig.colorFor(colorKind);
        return color.isPresent() ? component.withStyle(color.get()) : component;
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
        ResourceLocation chainId = resolveTierChain(sel, tier).orElse(null);
        if (chainId == null) return "";

        String name = compose(chainId, stack, sel.appliesTo(), rng, 0);
        if (name == null || name.isBlank()) return "";
        return applyTypeSynonym(name, stack, sel.appliesTo(), rng);
    }

    /**
     * Walk one chain directly without selector / item context — used by
     * the chain-editor preview to show what the chain would produce on
     * its own (no item-material substitution, no type-synonym wrap).
     * Context refs that read the stack (e.g. {@link #REF_ITEM_MATERIAL})
     * resolve to empty and are skipped by the composer.
     */
    public static String composeChainPreview(ResourceLocation chainId, RandomSource rng) {
        String name = compose(chainId, ItemStack.EMPTY, null, rng, 0);
        return name == null ? "" : name;
    }

    /**
     * Generate a name for a freshly-spawned {@link Mob} and apply it as
     * the vanilla {@code CustomName}. Intended to be called from a mixin
     * on {@code Mob.finalizeSpawn(...)} so it fires exactly once per
     * fresh spawn (never on chunk reload).
     *
     * <p>Probability (configurable via {@link NamingConfig}; defaults shown):
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
        ChanceKind colorKind;
        switch (cat) {
            case VILLAGER -> { chance = NamingConfig.chanceMobVillager(); nameVisible = false; colorKind = ChanceKind.MOB_VILLAGER; }
            case PASSIVE  -> { chance = NamingConfig.chanceMobPassive();  nameVisible = true;  colorKind = ChanceKind.MOB_PASSIVE; }
            default -> { return; }
        }

        if (rng.nextFloat() >= chance) return;

        String name = compose(CHAIN_MOB_NAME, ItemStack.EMPTY, null, rng, 0);
        if (name == null || name.isBlank()) return;

        mob.setCustomName(withColor(Component.literal(name), colorKind));
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
        List<NameSegment> shippedSegments = maybeChain.get().segments();
        int totalSegments = NamingConfig.effectiveSegmentCount(chainId, shippedSegments.size());
        List<Integer> order = NamingConfig.effectiveSegmentOrder(chainId, totalSegments);
        for (int segIdx : order) {
            if (NamingConfig.isSegmentRemoved(chainId, segIdx)) continue;
            NameSegment seg = NamingConfig.effectiveSegmentAt(chainId, segIdx, shippedSegments);
            if (seg == null) continue;

            float chance = NamingConfig.effectiveSegmentChance(chainId, segIdx, seg.chance());
            if (chance < 1f && rng.nextFloat() >= chance) continue;

            List<NameSegment.WeightedRef> refs = NamingConfig.effectiveSegmentRefs(chainId, segIdx, seg.refs());
            NameSegment.WeightedRef picked = pickWeighted(chainId, segIdx, refs, rng);
            if (picked == null) continue;

            String fragment = resolveRef(picked.ref(), stack, targetTagId, rng, depth + 1);
            if (fragment == null || fragment.isEmpty()) continue;

            // Newline emits BEFORE the connection so the connection can read
            // as a line-leading prefix (e.g. "Wielded by  " on a new line).
            // Suppressed when this segment is the first to fire so the chain
            // never starts with a stray newline.
            if (out.length() > 0
                && NamingConfig.effectiveSegmentNewline(chainId, segIdx, seg.newline())) {
                out.append('\n');
            }
            // Connection ALWAYS prepends — it's the segment's prefix, not a
            // between-segment glue. When a chain's first-firing segment has a
            // non-empty connection (e.g. "the " in {@code the_something},
            // "Forged by  " in a description chain), the prefix emits. The
            // {@code stripLeading} below covers the inverse case where a
            // segment's connection was authored as a separator (" ") and the
            // segment ends up firing first: any leading whitespace at the
            // chain level is dropped so older chains read identically.
            out.append(NamingConfig.effectiveSegmentConnection(chainId, segIdx, seg.connection()));
            out.append(fragment);
        }
        return out.toString().stripLeading();
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
        List<NamePool.PoolEntry> source = NamingConfig.effectivePoolEntries(pool);
        if (source.isEmpty() && !pool.entries().isEmpty()) {
            warnFallbackOnce(pool.id());
            source = pool.entries();
        }
        List<NamePool.PoolEntry> compatible = new ArrayList<>(source.size());
        for (NamePool.PoolEntry e : source) {
            // targetTagId == null means "no item context" (mob naming, chain
            // preview) — only entries without an item_types filter qualify.
            // Calling .contains(null) on the List.copyOf-backed itemTypes
            // would throw, hence the explicit null guard.
            if (e.itemTypes().isEmpty()
                || (targetTagId != null && e.itemTypes().contains(targetTagId))) {
                compatible.add(e);
            }
        }
        // Fallback: if every entry filtered out by item_types (e.g. type_synonyms
        // picked for a villager — no item context — or a shield item with no
        // shield entries in the pool), pick from the whole pool instead of
        // returning empty. Keeps segments from going blank when a fully-typed
        // pool is referenced in an incompatible context — without this, ~9%
        // of villagers spawn nameless because First Name rolls type_synonyms.
        if (compatible.isEmpty()) {
            if (source.isEmpty()) return "";
            return source.get(rng.nextInt(source.size())).text();
        }
        return compatible.get(rng.nextInt(compatible.size())).text();
    }

    /**
     * One-time-per-pool WARN when entry overrides zero out a pool's
     * effective entry list. Guards against hand-edited config that
     * would otherwise produce empty names — composer falls back to the
     * shipped entries so naming remains functional.
     */
    private static void warnFallbackOnce(ResourceLocation poolId) {
        if (FALLBACK_WARNED.add(poolId)) {
            LOGGER.warn("[AdventureItemNames] pool '{}' has been fully blanked by user/API overrides — "
                + "falling back to shipped entries. Edit pool_entry_overrides in adventureitemnames.json to fix.", poolId);
        }
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
