package games.brennan.adventureitemnames.forge;

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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.Optional;

/**
 * Forge mod entrypoint. Same shape as the NeoForge entrypoint but
 * imports from {@code net.minecraftforge.*} rather than
 * {@code net.neoforged.*}. The "name every rolled item" hook lives in
 * {@code common/}'s {@code LootTableMixin}.
 *
 * <p>Pushes the Forge config dir into {@link ConfigPaths} so the
 * common-module user-config loader knows where to find
 * {@code config/adventureitemnames.json}, and registers the built-in
 * "Adventure Time" data pack via {@code AddPackFindersEvent}.</p>
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesForge {

    public AdventureItemNamesForge() {
        ConfigPaths.set(FMLPaths.CONFIGDIR.get());
        UserConfigLoader.reload();

        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForge::onAddReloadListeners);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(AdventureItemNamesForge::onAddPackFinders);
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
