package games.brennan.adventureitemnames.neoforge;

import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.item.RandomChestItem;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

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
        event.addPackFinders(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "resourcepacks/atla"),
            PackType.SERVER_DATA,
            Component.literal("Adventure Item Names — ATLA Pack"),
            PackSource.BUILT_IN,
            /* alwaysActive = */ true,
            Pack.Position.TOP
        );
        event.addPackFinders(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "resourcepacks/adventuretime"),
            PackType.SERVER_DATA,
            Component.literal("Adventure Item Names — Adventure Time Pack"),
            PackSource.BUILT_IN,
            /* alwaysActive = */ true,
            Pack.Position.TOP
        );
    }
}
