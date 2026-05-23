package games.brennan.adventureitemnames.fabric.client;

import com.mojang.brigadier.CommandDispatcher;
import games.brennan.adventureitemnames.client.ClientInit;
import games.brennan.adventureitemnames.internal.BundledPackLoader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.CommandBuildContext;

/**
 * Fabric-side client entry. Registers the unbound {@code Open config
 * screen} keybind, the {@code /adventureitemnames config} client
 * command, and a per-tick poll that opens the screen when the keybind
 * fires. Also kicks off the {@link BundledPackLoader} preload so the
 * Mod Menu {@code Config} button has data to show at the title screen
 * before any world has been loaded.
 *
 * <p>Wired into {@code fabric.mod.json} under the {@code client}
 * entrypoint array — see the manifest patch in this PR.
 */
@Environment(EnvType.CLIENT)
public final class AdventureItemNamesFabricClient implements ClientModInitializer {

    private KeyMapping configKey;

    @Override
    public void onInitializeClient() {
        // Preload shipped packs so the title-screen-launched config screen
        // has Chains / Datapacks data even before any world is loaded.
        BundledPackLoader.loadIntoRegistry();

        configKey = ClientInit.createKeyMapping();
        KeyBindingHelper.registerKeyBinding(configKey);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (ClientInit.shouldOpen(configKey)) {
                ClientInit.openConfigScreen();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register(this::registerCommand);
    }

    private void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                 CommandBuildContext ctx) {
        dispatcher.register(
            ClientCommandManager.literal("adventureitemnames")
                .then(ClientCommandManager.literal("config").executes(c -> {
                    ClientInit.openConfigScreen();
                    return 1;
                }))
        );
    }
}
