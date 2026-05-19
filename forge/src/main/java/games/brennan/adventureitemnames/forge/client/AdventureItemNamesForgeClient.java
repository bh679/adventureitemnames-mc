package games.brennan.adventureitemnames.forge.client;

import games.brennan.adventureitemnames.client.ClientInit;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Forge-side client entry. Wires up the keybind on the mod event bus
 * and the {@code /adventureitemnames config} client command + per-tick
 * poll on {@link MinecraftForge#EVENT_BUS}.
 *
 * <p>Instantiated from {@link games.brennan.adventureitemnames.forge.AdventureItemNamesForge}
 * via a {@code Dist.CLIENT} guard.
 */
public final class AdventureItemNamesForgeClient {

    private static KeyMapping configKey;

    private AdventureItemNamesForgeClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(AdventureItemNamesForgeClient::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForgeClient::onRegisterClientCommands);
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
