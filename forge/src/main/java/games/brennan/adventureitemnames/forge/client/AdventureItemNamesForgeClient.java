package games.brennan.adventureitemnames.forge.client;

import games.brennan.adventureitemnames.client.ClientInit;
import games.brennan.adventureitemnames.client.ConfigScreen;
import games.brennan.adventureitemnames.internal.BundledPackLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Forge-side client entry. Wires up the keybind on the mod event bus
 * and the {@code /adventureitemnames config} client command + per-tick
 * poll on {@link MinecraftForge#EVENT_BUS}. Also registers the
 * {@link ConfigScreenHandler.ConfigScreenFactory} extension so the
 * "Config" button appears next to the mod in the Mods list.
 *
 * <p>Instantiated from {@link games.brennan.adventureitemnames.forge.AdventureItemNamesForge}
 * via a {@code Dist.CLIENT} guard.
 */
public final class AdventureItemNamesForgeClient {

    private static KeyMapping configKey;

    private AdventureItemNamesForgeClient() {}

    public static void register(IEventBus modBus) {
        // Preload shipped packs so the title-screen-launched config screen
        // has Chains / Datapacks data even before any world is loaded.
        BundledPackLoader.loadIntoRegistry();

        modBus.addListener(AdventureItemNamesForgeClient::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForgeClient::onRegisterClientCommands);

        // Mods-list "Config" button. Passes the mods screen as parent so
        // ConfigScreen.onClose() returns there on Esc.
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> new ConfigScreen(parent)));
    }

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        configKey = ClientInit.createKeyMapping();
        event.register(configKey);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (configKey != null && ClientInit.shouldOpen(configKey)) {
            ClientInit.openConfigScreen();
        }
    }

    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("adventureitemnames")
                .then(Commands.literal("config").executes(c -> {
                    ClientInit.openConfigScreen();
                    return 1;
                }))
        );
    }
}
