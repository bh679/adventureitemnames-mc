package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.MobCategory;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Mutable disable-rule snapshot for one layer (datapack / user / API).
 * Not thread-safe on its own — callers in {@code NamingConfig} hold a
 * shared lock when touching these fields.
 *
 * <p>{@link IdSet} stores both exact ids and {@code namespace:*} wildcards
 * — wildcards can't go through {@code ResourceLocation} because {@code *}
 * is not a legal path character.</p>
 */
public final class DisableSet {

    public final IdSet pools = new IdSet();
    public final IdSet chains = new IdSet();
    public final IdSet selectors = new IdSet();

    public final IdSet itemTags = new IdSet();
    public final IdSet itemIds = new IdSet();

    public final EnumSet<MobCategory> mobCategories = EnumSet.noneOf(MobCategory.class);
    public final IdSet entityTags = new IdSet();
    public final IdSet entityIds = new IdSet();

    public void clear() {
        pools.clear();
        chains.clear();
        selectors.clear();
        itemTags.clear();
        itemIds.clear();
        mobCategories.clear();
        entityTags.clear();
        entityIds.clear();
    }

    /** Union the contents of {@code other} into this set. */
    public void mergeFrom(DisableSet other) {
        pools.addAll(other.pools);
        chains.addAll(other.chains);
        selectors.addAll(other.selectors);
        itemTags.addAll(other.itemTags);
        itemIds.addAll(other.itemIds);
        mobCategories.addAll(other.mobCategories);
        entityTags.addAll(other.entityTags);
        entityIds.addAll(other.entityIds);
    }

    /** Holds exact {@link ResourceLocation} ids plus namespace wildcards. */
    public static final class IdSet {
        private final Set<ResourceLocation> ids = new HashSet<>();
        private final Set<String> wildcardNamespaces = new HashSet<>();

        /**
         * Parse a string id. Returns true on success. Supports
         * {@code namespace:*} wildcards.
         */
        public boolean addRaw(String raw) {
            if (raw == null) return false;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) return false;
            int colon = trimmed.indexOf(':');
            String ns;
            String path;
            if (colon >= 0) {
                ns = trimmed.substring(0, colon);
                path = trimmed.substring(colon + 1);
            } else {
                ns = "minecraft";
                path = trimmed;
            }
            if (path.equals("*")) {
                if (ns.isEmpty()) return false;
                wildcardNamespaces.add(ns);
                return true;
            }
            ResourceLocation rl = ResourceLocation.tryParse(trimmed);
            if (rl == null) return false;
            ids.add(rl);
            return true;
        }

        public void add(ResourceLocation rl) {
            if (rl != null) ids.add(rl);
        }

        public boolean contains(ResourceLocation rl) {
            if (rl == null) return false;
            if (ids.contains(rl)) return true;
            return wildcardNamespaces.contains(rl.getNamespace());
        }

        public boolean remove(ResourceLocation rl) {
            if (rl == null) return false;
            return ids.remove(rl);
        }

        public void clear() {
            ids.clear();
            wildcardNamespaces.clear();
        }

        public boolean isEmpty() {
            return ids.isEmpty() && wildcardNamespaces.isEmpty();
        }

        public void addAll(IdSet other) {
            this.ids.addAll(other.ids);
            this.wildcardNamespaces.addAll(other.wildcardNamespaces);
        }
    }
}
