package games.brennan.adventureitemnames.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import games.brennan.adventureitemnames.client.ConfigScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Mod Menu integration — exposes the in-game config hub as the "Config"
 * button next to "Adventure Item Names" in Mod Menu's Mods list.
 *
 * <p>Wired in via the {@code modmenu} entrypoint in {@code fabric.mod.json}.
 * Mod Menu is an optional compile-time / dev-runtime dependency: if it is
 * not installed at runtime, this class is never loaded (the entrypoint is
 * simply ignored).
 *
 * <p>The factory passes the Mods list screen through as the parent so the
 * existing {@link ConfigScreen#onClose()} behaviour returns the user to
 * the Mods list on Esc.
 */
@Environment(EnvType.CLIENT)
public final class AdventureItemNamesModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
