package games.brennan.adventureitemnames.api;

import games.brennan.adventureitemnames.internal.DisableSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Public configuration surface for enabling/disabling parts of the naming
 * system. Three layers stack monotonically — any source disables a thing
 * → it's disabled:
 *
 * <ul>
 *   <li><b>Datapack layer</b> — JSON files under {@code data/<modid>/disabled/}.
 *       Multiple datapacks contribute different files; all union.</li>
 *   <li><b>User layer</b> — {@code <configDir>/adventureitemnames.json}.
 *       Loaded at startup and re-read on every {@code /reload}.</li>
 *   <li><b>API layer</b> — runtime mutation via the {@code disableXxx} /
 *       {@code enableXxx} methods on this class.</li>
 * </ul>
 *
 * <p>{@code enableXxx} only clears the API-layer entry for the given id;
 * datapack and user-config disables remain in effect. Counter-allowlists
 * in JSON files are deliberately not supported — to override a
 * lower-layer disable, ship a higher-priority datapack or call the API.
 *
 * <p>JSON layers accept {@code namespace:*} wildcards in any id list.
 * {@code adventureitemnames:context/*} virtual refs (e.g. {@code item_material})
 * are never affected by disable rules — they short-circuit ref resolution.
 *
 * <p>Disable is silent and graceful: a disabled pool returns the empty
 * string, a chain segment whose refs are all disabled contributes
 * nothing, a disabled selector skips loot naming, a disabled mob category
 * skips spawn naming. No exceptions are thrown.
 *
 * <p><b>Note:</b> {@code isItemEnabled(ItemStack)} can only subtract from
 * coverage. To <em>add</em> naming to an item class that no selector
 * matches today, ship a selector JSON — there is no allowlist counterpart.
 */
public final class NamingConfig {

    private static final Object LOCK = new Object();
    private static final DisableSet DATAPACK = new DisableSet();
    private static final DisableSet USER = new DisableSet();
    private static final DisableSet API = new DisableSet();

    private NamingConfig() {}

    // ─────────────────────────────────────────────────────────────
    // Internal layer setters (called from ConfigListener / UserConfigLoader)
    // ─────────────────────────────────────────────────────────────

    /** Replace the datapack-layer disable set. Internal — called by the reload listener. */
    public static void setDatapackLayer(DisableSet newLayer) {
        synchronized (LOCK) {
            DATAPACK.clear();
            if (newLayer != null) DATAPACK.mergeFrom(newLayer);
        }
    }

    /** Replace the user-config-layer disable set. Internal — called by the user-config loader. */
    public static void setUserLayer(DisableSet newLayer) {
        synchronized (LOCK) {
            USER.clear();
            if (newLayer != null) USER.mergeFrom(newLayer);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // API-layer setters
    // ─────────────────────────────────────────────────────────────

    public static void disablePool(ResourceLocation id)     { synchronized (LOCK) { API.pools.add(id); } }
    public static void enablePool(ResourceLocation id)      { synchronized (LOCK) { API.pools.remove(id); } }
    public static void disableChain(ResourceLocation id)    { synchronized (LOCK) { API.chains.add(id); } }
    public static void enableChain(ResourceLocation id)     { synchronized (LOCK) { API.chains.remove(id); } }
    public static void disableSelector(ResourceLocation id) { synchronized (LOCK) { API.selectors.add(id); } }
    public static void enableSelector(ResourceLocation id)  { synchronized (LOCK) { API.selectors.remove(id); } }

    public static void disableItem(ResourceLocation itemId) { synchronized (LOCK) { API.itemIds.add(itemId); } }
    public static void enableItem(ResourceLocation itemId)  { synchronized (LOCK) { API.itemIds.remove(itemId); } }
    public static void disableItemTag(TagKey<Item> tag) {
        if (tag != null) synchronized (LOCK) { API.itemTags.add(tag.location()); }
    }
    public static void enableItemTag(TagKey<Item> tag) {
        if (tag != null) synchronized (LOCK) { API.itemTags.remove(tag.location()); }
    }

    public static void disableMobCategory(MobCategory cat) {
        if (cat != null) synchronized (LOCK) { API.mobCategories.add(cat); }
    }
    public static void enableMobCategory(MobCategory cat) {
        if (cat != null) synchronized (LOCK) { API.mobCategories.remove(cat); }
    }

    public static void disableEntity(ResourceLocation entityId) { synchronized (LOCK) { API.entityIds.add(entityId); } }
    public static void enableEntity(ResourceLocation entityId)  { synchronized (LOCK) { API.entityIds.remove(entityId); } }
    public static void disableEntityTag(TagKey<EntityType<?>> tag) {
        if (tag != null) synchronized (LOCK) { API.entityTags.add(tag.location()); }
    }
    public static void enableEntityTag(TagKey<EntityType<?>> tag) {
        if (tag != null) synchronized (LOCK) { API.entityTags.remove(tag.location()); }
    }

    // ─────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────

    public static boolean isPoolEnabled(ResourceLocation id) {
        if (id == null) return true;
        synchronized (LOCK) {
            return !DATAPACK.pools.contains(id) && !USER.pools.contains(id) && !API.pools.contains(id);
        }
    }

    public static boolean isChainEnabled(ResourceLocation id) {
        if (id == null) return true;
        synchronized (LOCK) {
            return !DATAPACK.chains.contains(id) && !USER.chains.contains(id) && !API.chains.contains(id);
        }
    }

    public static boolean isSelectorEnabled(ResourceLocation id) {
        if (id == null) return true;
        synchronized (LOCK) {
            return !DATAPACK.selectors.contains(id) && !USER.selectors.contains(id) && !API.selectors.contains(id);
        }
    }

    /**
     * Per-item gate checked in addition to the selector gate. Returns
     * false when the stack's item id is in any layer's item-id disable
     * list, or when any of the stack's item tags is in any layer's
     * item-tag disable list. Tags are resolved lazily here so the tag
     * system doesn't have to be ready at config-load time.
     */
    public static boolean isItemEnabled(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        synchronized (LOCK) {
            if (itemId != null
                && (DATAPACK.itemIds.contains(itemId)
                 || USER.itemIds.contains(itemId)
                 || API.itemIds.contains(itemId))) {
                return false;
            }
            boolean[] disabledByTag = { false };
            stack.getTags().forEach(tagKey -> {
                if (disabledByTag[0]) return;
                ResourceLocation tagId = tagKey.location();
                if (DATAPACK.itemTags.contains(tagId)
                 || USER.itemTags.contains(tagId)
                 || API.itemTags.contains(tagId)) {
                    disabledByTag[0] = true;
                }
            });
            return !disabledByTag[0];
        }
    }

    public static boolean isMobCategoryEnabled(MobCategory cat) {
        if (cat == null) return false;
        synchronized (LOCK) {
            return !DATAPACK.mobCategories.contains(cat)
                && !USER.mobCategories.contains(cat)
                && !API.mobCategories.contains(cat);
        }
    }

    /**
     * Per-entity gate checked in addition to the mob-category gate.
     * Returns false when the entity type id is in any layer's entity-id
     * disable list, or any of its type tags is in any entity-tag list.
     */
    public static boolean isEntityEnabled(EntityType<?> type) {
        if (type == null) return false;
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        synchronized (LOCK) {
            if (entityId != null
                && (DATAPACK.entityIds.contains(entityId)
                 || USER.entityIds.contains(entityId)
                 || API.entityIds.contains(entityId))) {
                return false;
            }
            boolean[] disabledByTag = { false };
            type.builtInRegistryHolder().tags().forEach(tagKey -> {
                if (disabledByTag[0]) return;
                ResourceLocation tagId = tagKey.location();
                if (DATAPACK.entityTags.contains(tagId)
                 || USER.entityTags.contains(tagId)
                 || API.entityTags.contains(tagId)) {
                    disabledByTag[0] = true;
                }
            });
            return !disabledByTag[0];
        }
    }
}
