package games.brennan.adventureitemnames.forge;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.item.RandomChestItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Forge mod entrypoint. Same shape as the NeoForge entrypoint but
 * imports from {@code net.minecraftforge.*} rather than
 * {@code net.neoforged.*}. The "name every rolled item" hook lives in
 * {@code common/}'s {@code LootTableMixin}.
 *
 * <p>Pushes the Forge config dir into {@link ConfigPaths} so the
 * common-module user-config loader knows where to find
 * {@code config/adventureitemnames.json}.</p>
 *
 * <p>Registers the built-in ATLA datapack on the mod event bus via
 * {@link AddPackFindersEvent}. Forge's API doesn't expose a
 * "default-enabled-but-toggleable" mode — we ship the pack as
 * <em>always active</em> at the loader level. Users can still suppress
 * ATLA names through the {@code NamingConfig} disable layer (see the
 * user config file).</p>
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesForge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, "adventureitemnames");

    public static final RegistryObject<Item> RANDOM_CHEST =
        ITEMS.register("random_chest", () -> new RandomChestItem(new Item.Properties()));

    public AdventureItemNamesForge(IEventBus modBus) {
        ConfigPaths.set(FMLPaths.CONFIGDIR.get());
        UserConfigLoader.reload();

        ITEMS.register(modBus);
        modBus.addListener(AdventureItemNamesForge::onBuildCreativeTab);

        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForge::onAddReloadListeners);
        modBus.addListener(AdventureItemNamesForge::onAddPackFinders);
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RANDOM_CHEST.get());
        }
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NameRegistry.poolListener());
        event.addListener(NameRegistry.chainListener());
        event.addListener(NameRegistry.extensionListener());
        event.addListener(NameRegistry.selectorListener());
        event.addListener(NameRegistry.configListener());
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;

        var modFile = ModList.get().getModFileById("adventureitemnames");
        if (modFile == null) {
            LOGGER.warn("[AdventureItemNames] mod file lookup failed; ATLA pack will not be registered");
            return;
        }
        Path resourcePath = modFile.getFile().findResource("resourcepacks/atla");
        if (resourcePath == null) {
            LOGGER.warn("[AdventureItemNames] ATLA resource path not found inside mod jar");
            return;
        }

        PackLocationInfo location = new PackLocationInfo(
            "mod/adventureitemnames/atla",
            Component.literal("Adventure Item Names — ATLA Pack"),
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
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }
}
