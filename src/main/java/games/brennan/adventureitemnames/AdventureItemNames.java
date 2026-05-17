package games.brennan.adventureitemnames;

import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.loot.ModLootModifiers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Adventure Item Names mod entrypoint.
 *
 * <p>Registers the global-loot-modifier serializer codec on the mod bus
 * and the data-driven naming-registry reload listeners on the game bus.
 * No registered blocks/items/entities; this mod ships only data + a
 * loot-table hook + a public Java API ({@link games.brennan.adventureitemnames.api.NameComposer}).</p>
 */
@Mod(AdventureItemNames.MOD_ID)
public final class AdventureItemNames {

    public static final String MOD_ID = "adventureitemnames";

    public AdventureItemNames(IEventBus modBus) {
        ModLootModifiers.register(modBus);
        NeoForge.EVENT_BUS.addListener(NameRegistry::onAddReloadListeners);
    }
}
