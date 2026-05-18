package games.brennan.adventureitemnames.neoforge;

import games.brennan.adventureitemnames.internal.NameRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * NeoForge mod entrypoint. Registers the three datapack-style reload
 * listeners via NeoForge's {@code AddReloadListenerEvent}. The actual
 * "name every rolled item" hook lives in {@code common/}'s
 * {@code LootTableMixin}.
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesNeoForge {

    public AdventureItemNamesNeoForge(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(AdventureItemNamesNeoForge::onAddReloadListeners);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NameRegistry.poolListener());
        event.addListener(NameRegistry.chainListener());
        event.addListener(NameRegistry.selectorListener());
    }
}
