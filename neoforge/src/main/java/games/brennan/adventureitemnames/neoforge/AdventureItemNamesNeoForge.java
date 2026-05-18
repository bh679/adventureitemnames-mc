package games.brennan.adventureitemnames.neoforge;

import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.Optional;

/**
 * NeoForge mod entrypoint. Registers the datapack-style reload listeners
 * via {@code AddReloadListenerEvent}, and registers the built-in
 * "Adventure Time" data pack via {@code AddPackFindersEvent}.
 *
 * <p>Pushes the NeoForge config dir into {@link ConfigPaths} so the
 * common-module user-config loader knows where to find
 * {@code config/adventureitemnames.json}.</p>
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesNeoForge {

    public AdventureItemNamesNeoForge(IEventBus modBus) {
        ConfigPaths.set(FMLPaths.CONFIGDIR.get());
        UserConfigLoader.reload();

        NeoForge.EVENT_BUS.addListener(AdventureItemNamesNeoForge::onAddReloadListeners);
        modBus.addListener(AdventureItemNamesNeoForge::onAddPackFinders);
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NameRegistry.poolListener());
        event.addListener(NameRegistry.chainListener());
        event.addListener(NameRegistry.selectorListener());
        event.addListener(NameRegistry.configListener());
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        var modFile = ModList.get().getModFileById("adventureitemnames");
        if (modFile == null) return;
        var path = modFile.getFile().findResource("resourcepacks/adventuretime");
        var location = new PackLocationInfo(
                "builtin/adventureitemnames/adventuretime",
                Component.literal("Adventure Time"),
                PackSource.BUILT_IN,
                Optional.empty()
        );
        var resources = new PathPackResources.PathResourcesSupplier(path);
        Pack pack = Pack.readMetaAndCreate(
                location,
                resources,
                PackType.SERVER_DATA,
                new PackSelectionConfig(true, Pack.Position.TOP, false)
        );
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }
}
