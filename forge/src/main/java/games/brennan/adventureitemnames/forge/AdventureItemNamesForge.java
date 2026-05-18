package games.brennan.adventureitemnames.forge;

import games.brennan.adventureitemnames.internal.NameRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge mod entrypoint. Same shape as the NeoForge entrypoint but
 * imports from {@code net.minecraftforge.*} rather than
 * {@code net.neoforged.*}. The "name every rolled item" hook lives in
 * {@code common/}'s {@code LootTableMixin}.
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesForge {

    public AdventureItemNamesForge() {
        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForge::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NameRegistry.poolListener());
        event.addListener(NameRegistry.chainListener());
        event.addListener(NameRegistry.selectorListener());
    }
}
