package games.brennan.adventureitemnames.compat;

import net.minecraft.resources.ResourceLocation;

/**
 * Version-bridging factory for {@link ResourceLocation}. MC 1.21 made the
 * {@code ResourceLocation} constructors private and replaced them with the
 * {@code fromNamespaceAndPath} / {@code parse} static factories; 1.20.1 still
 * uses the public constructors. Every {@code ResourceLocation.fromNamespaceAndPath}
 * / {@code ResourceLocation.parse} call site in the mod routes through here so the
 * single Stonecutter conditional lives in one place.
 *
 * <p>{@code ResourceLocation.tryParse} exists on both versions and is used directly
 * elsewhere — it does not need bridging.</p>
 */
public final class Ids {

    private Ids() {}

    /** Build a namespaced id. 1.21.1 {@code fromNamespaceAndPath}; 1.20.1 constructor. */
    public static ResourceLocation of(String namespace, String path) {
        //? if >=1.21.1 {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new ResourceLocation(namespace, path);*/
        //?}
    }

    /** Parse a {@code namespace:path} string. 1.21.1 {@code parse}; 1.20.1 constructor. */
    public static ResourceLocation parse(String id) {
        //? if >=1.21.1 {
        return ResourceLocation.parse(id);
        //?} else {
        /*return new ResourceLocation(id);*/
        //?}
    }
}
