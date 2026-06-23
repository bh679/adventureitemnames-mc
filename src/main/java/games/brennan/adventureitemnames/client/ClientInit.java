package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Common-side hooks for the in-game config screen. Each loader's client
 * entrypoint calls {@link #createKeyMapping()} once to obtain the
 * unbound default keybind, polls {@link #shouldOpen(KeyMapping)} once
 * per client tick, and calls {@link #openConfigScreen()} on a positive
 * tick or from a {@code /adventureitemnames config} command.
 *
 * <p>The loader-glue classes own keybind / command registration because
 * each loader has a different event bus; this class owns the screen
 * itself and stays loader-agnostic.
 */
@Environment(EnvType.CLIENT)
public final class ClientInit {

    public static final String KEY_CATEGORY = "key.categories.adventureitemnames";
    public static final String KEY_OPEN_CONFIG = "key.adventureitemnames.open_config";

    private ClientInit() {}

    public static KeyMapping createKeyMapping() {
        return new KeyMapping(
            KEY_OPEN_CONFIG,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(),
            KEY_CATEGORY
        );
    }

    public static boolean shouldOpen(KeyMapping mapping) {
        boolean any = false;
        while (mapping.consumeClick()) any = true;
        return any;
    }

    /**
     * Push the hub screen onto the client. Deferred to the next tick via
     * {@link Minecraft#tell} so {@code setScreen} runs after any
     * in-flight chat-close (which itself calls {@code setScreen(null)}).
     * Without the defer, invoking from a client-command executor opens
     * the hub and then loses it to the chat-close stomp on the same tick.
     */
    public static void openConfigScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new ConfigScreen(null)));
    }
}
