package games.brennan.adventureitemnames.neoforge.client;

import games.brennan.adventureitemnames.client.ClientInit;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge-side client entry. Registers the keybind via the mod event
 * bus and the {@code /adventureitemnames config} client command via
 * {@link RegisterClientCommandsEvent}. The per-tick poll fires on the
 * NeoForge game-event bus.
 *
 * <p>Instantiated from {@link games.brennan.adventureitemnames.neoforge.AdventureItemNamesNeoForge}
 * — see the construction call there. Pure client-side; never touched
 * on a dedicated-server boot.
 */
public final class AdventureItemNamesNeoForgeClient {

    private static KeyMapping configKey;

    private AdventureItemNamesNeoForgeClient() {}

    /** Wire up the game-event listeners that need a {@code Mod.Bus.GAME} subscription. */
    public static void register(IEventBus modBus) {
        modBus.addListener(AdventureItemNamesNeoForgeClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(AdventureItemNamesNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(AdventureItemNamesNeoForgeClient::onRegisterClientCommands);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        configKey = ClientInit.createKeyMapping();
        event.register(configKey);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (configKey != null && ClientInit.shouldOpen(configKey)) {
            ClientInit.openConfigScreen();
        }
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("adventureitemnames")
                .then(Commands.literal("config").executes(c -> {
                    ClientInit.openConfigScreen();
                    return 1;
                }))
        );
    }
}
