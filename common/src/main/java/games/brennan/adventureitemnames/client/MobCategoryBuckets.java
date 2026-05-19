package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.MobCategory;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies every registered vanilla {@link EntityType} into one of our
 * {@link MobCategory} buckets ({@code VILLAGER} / {@code PASSIVE}) — or
 * neither — for the per-spawn-chance-category mob sub-menus.
 *
 * <p>Vanilla {@link net.minecraft.world.entity.MobCategory} (the MC enum,
 * distinct from ours) classifies entities at registration time and is
 * trivially queryable via {@link EntityType#getCategory()}. We map that
 * to our two naming buckets plus a hardcoded villager allow-list, since
 * vanilla puts villagers in {@code CREATURE} alongside cows and chickens.
 *
 * <p>{@link games.brennan.adventureitemnames.api.NameComposer#categorize}
 * is the source of truth at runtime — it uses {@code instanceof} +
 * {@code Enemy} checks per-spawn. The UI bucketing is an approximation
 * that errs on the side of listing more mobs (e.g. {@code wolf}) so the
 * user can configure them; the runtime composer still respects the
 * {@code Enemy} marker when actually rolling.
 *
 * <p>The classification is cached after first build — the vanilla
 * {@code EntityType} registry is frozen at server boot and doesn't
 * change at runtime.
 */
@Environment(EnvType.CLIENT)
public final class MobCategoryBuckets {

    /** Vanilla entities we treat as VILLAGER bucket regardless of {@code getCategory()}. */
    private static final Set<ResourceLocation> VILLAGER_IDS = Set.of(
        ResourceLocation.fromNamespaceAndPath("minecraft", "villager"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "wandering_trader"));

    /**
     * Vanilla {@code MobCategory} values that map into our PASSIVE
     * bucket. Excludes {@code MONSTER}, {@code MISC}. Everything else
     * is some flavour of non-hostile creature/water/ambient.
     */
    private static final Set<net.minecraft.world.entity.MobCategory> PASSIVE_VANILLA_CATEGORIES = Set.of(
        net.minecraft.world.entity.MobCategory.CREATURE,
        net.minecraft.world.entity.MobCategory.WATER_CREATURE,
        net.minecraft.world.entity.MobCategory.WATER_AMBIENT,
        net.minecraft.world.entity.MobCategory.UNDERGROUND_WATER_CREATURE,
        net.minecraft.world.entity.MobCategory.AMBIENT,
        net.minecraft.world.entity.MobCategory.AXOLOTLS);

    private static volatile Map<MobCategory, List<EntityType<?>>> CACHE = null;

    private MobCategoryBuckets() {}

    /**
     * Sorted list of entity types in {@code category} (by id, alphabetical).
     * Returns an empty list when our category has no vanilla equivalents.
     */
    public static List<EntityType<?>> entitiesIn(MobCategory category) {
        if (category == null) return List.of();
        return ensureBuilt().getOrDefault(category, List.of());
    }

    /** Invalidate the cache so the next call re-scans the registry. Use after {@code /reload}. */
    public static void invalidate() {
        CACHE = null;
    }

    private static Map<MobCategory, List<EntityType<?>>> ensureBuilt() {
        Map<MobCategory, List<EntityType<?>>> snap = CACHE;
        if (snap != null) return snap;
        synchronized (MobCategoryBuckets.class) {
            snap = CACHE;
            if (snap != null) return snap;
            snap = build();
            CACHE = snap;
            return snap;
        }
    }

    private static Map<MobCategory, List<EntityType<?>>> build() {
        EnumMap<MobCategory, List<EntityType<?>>> out = new EnumMap<>(MobCategory.class);
        List<EntityType<?>> villagers = new ArrayList<>();
        List<EntityType<?>> passives = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            if (VILLAGER_IDS.contains(id)) {
                villagers.add(type);
                continue;
            }
            net.minecraft.world.entity.MobCategory vanillaCat = type.getCategory();
            if (PASSIVE_VANILLA_CATEGORIES.contains(vanillaCat)) {
                passives.add(type);
            }
        }
        Comparator<EntityType<?>> byId = Comparator.comparing(
            t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString());
        villagers.sort(byId);
        passives.sort(byId);
        out.put(MobCategory.VILLAGER, Collections.unmodifiableList(villagers));
        out.put(MobCategory.PASSIVE, Collections.unmodifiableList(passives));
        return Collections.unmodifiableMap(out);
    }
}
