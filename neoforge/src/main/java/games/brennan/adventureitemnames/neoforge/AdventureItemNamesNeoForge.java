package games.brennan.adventureitemnames.neoforge;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.item.RandomChestItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * NeoForge mod entrypoint. Registers the datapack-style reload listeners
 * via {@code AddReloadListenerEvent}, registers the built-in ATLA and
 * Adventure Time data packs via {@code AddPackFindersEvent} (both
 * default-enabled), and registers the {@code random_chest} creative
 * test items.
 *
 * <p>Pushes the NeoForge config dir into {@link ConfigPaths} so the
 * common-module user-config loader knows where to find
 * {@code config/adventureitemnames.json}.</p>
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesNeoForge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("adventureitemnames");

    public static final DeferredItem<Item> RANDOM_CHEST =
        ITEMS.register("random_chest", () -> new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.DEFAULT));

    public static final DeferredItem<Item> RANDOM_NAMED_CHEST =
        ITEMS.register("random_named_chest", () -> new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ALWAYS_NAMED));

    public static final DeferredItem<Item> RANDOM_ENCHANTED_CHEST =
        ITEMS.register("random_enchanted_chest", () -> new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ENCHANTED));

    public AdventureItemNamesNeoForge(IEventBus modBus) {
        ConfigPaths.set(FMLPaths.CONFIGDIR.get());
        UserConfigLoader.reload();

        ITEMS.register(modBus);
        modBus.addListener(AdventureItemNamesNeoForge::onBuildCreativeTab);

        NeoForge.EVENT_BUS.addListener(AdventureItemNamesNeoForge::onAddReloadListeners);
        modBus.addListener(AdventureItemNamesNeoForge::onAddPackFinders);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            games.brennan.adventureitemnames.neoforge.client.AdventureItemNamesNeoForgeClient.register(modBus);
        }
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RANDOM_CHEST.get());
            event.accept(RANDOM_NAMED_CHEST.get());
            event.accept(RANDOM_ENCHANTED_CHEST.get());
        }
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NameRegistry.poolListener());
        event.addListener(NameRegistry.chainListener());
        event.addListener(NameRegistry.selectorListener());
        event.addListener(NameRegistry.configListener());
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;

        var modFile = ModList.get().getModFileById("adventureitemnames");
        if (modFile == null) {
            LOGGER.warn("[AdventureItemNames] mod file lookup failed; built-in data packs will not be registered");
            return;
        }

        registerBuiltinPack(event, modFile.getFile().findResource("resourcepacks/atla"),
            "atla", "Adventure Item Names — ATLA Pack");
        registerBuiltinPack(event, modFile.getFile().findResource("resourcepacks/adventuretime"),
            "adventuretime", "Adventure Item Names — Adventure Time Pack");
    }

    private static void registerBuiltinPack(AddPackFindersEvent event, Path resourcePath,
                                            String packPath, String displayName) {
        if (resourcePath == null || !Files.exists(resourcePath)) {
            LOGGER.warn("[AdventureItemNames] built-in pack 'resourcepacks/{}' not found inside mod jar", packPath);
            return;
        }
        PackLocationInfo location = new PackLocationInfo(
            "mod/adventureitemnames/" + packPath,
            Component.literal(displayName),
            PackSource.BUILT_IN,
            Optional.empty()
        );
        Pack.ResourcesSupplier supplier = new PathPackResources.PathResourcesSupplier(resourcePath);
        PackSelectionConfig selectionConfig = new PackSelectionConfig(
            /* required = */ true,
            Pack.Position.TOP,
            /* fixedPosition = */ false
        );
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.SERVER_DATA, selectionConfig);
        if (pack == null) {
            LOGGER.warn("[AdventureItemNames] Pack.readMetaAndCreate returned null for 'resourcepacks/{}' (path={})", packPath, resourcePath);
            return;
        }
        LOGGER.info("[AdventureItemNames] registered built-in data pack '{}' from {}", packPath, resourcePath);
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }
}
