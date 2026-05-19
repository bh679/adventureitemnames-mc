package games.brennan.adventureitemnames.fabric;

import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.item.RandomChestItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fabric mod entrypoint. Wraps each {@link NameRegistry} listener with a
 * Fabric-id identifier and registers it on the server-data resource
 * manager so {@code /reload} picks them up alongside vanilla recipes,
 * advancements, loot tables, and tags.
 *
 * <p>Also pushes the Fabric config dir into {@link ConfigPaths} so the
 * common-module {@link UserConfigLoader} can find
 * {@code config/adventureitemnames.json}, and triggers an initial read
 * of that file at init.</p>
 */
public final class AdventureItemNamesFabric implements ModInitializer {

    public static final Item RANDOM_CHEST = Registry.register(
        BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "random_chest"),
        new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.DEFAULT)
    );

    public static final Item RANDOM_NAMED_CHEST = Registry.register(
        BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "random_named_chest"),
        new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ALWAYS_NAMED)
    );

    public static final Item RANDOM_ENCHANTED_CHEST = Registry.register(
        BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "random_enchanted_chest"),
        new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ENCHANTED)
    );

    @Override
    public void onInitialize() {
        ConfigPaths.set(FabricLoader.getInstance().getConfigDir());
        UserConfigLoader.reload();

        ResourceManagerHelper rh = ResourceManagerHelper.get(PackType.SERVER_DATA);
        rh.registerReloadListener(wrap(NameRegistry.poolListener(),      "pools"));
        rh.registerReloadListener(wrap(NameRegistry.chainListener(),     "chains"));
        rh.registerReloadListener(wrap(NameRegistry.extensionListener(), "chain_extensions"));
        rh.registerReloadListener(wrap(NameRegistry.selectorListener(),  "selectors"));
        rh.registerReloadListener(wrap(NameRegistry.configListener(),    "disabled"));

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
            .register(entries -> {
                entries.accept(RANDOM_CHEST);
                entries.accept(RANDOM_NAMED_CHEST);
                entries.accept(RANDOM_ENCHANTED_CHEST);
            });

        FabricLoader.getInstance().getModContainer("adventureitemnames").ifPresent(container ->
            ResourceManagerHelper.registerBuiltinResourcePack(
                ResourceLocation.fromNamespaceAndPath("adventureitemnames", "atla"),
                container,
                Component.literal("Adventure Item Names — ATLA Pack"),
                ResourcePackActivationType.DEFAULT_ENABLED
            )
        );
    }

    private static IdentifiableResourceReloadListener wrap(PreparableReloadListener inner, String id) {
        ResourceLocation fabricId = ResourceLocation.fromNamespaceAndPath("adventureitemnames", id);
        return new IdentifiableResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return fabricId; }
            @Override public List<ResourceLocation> getFabricDependencies() { return List.of(); }
            @Override public String getName() { return fabricId.toString(); }
            @Override public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager mgr,
                                                            ProfilerFiller prep, ProfilerFiller apply,
                                                            Executor prepExec, Executor applyExec) {
                return inner.reload(barrier, mgr, prep, apply, prepExec, applyExec);
            }
        };
    }
}
