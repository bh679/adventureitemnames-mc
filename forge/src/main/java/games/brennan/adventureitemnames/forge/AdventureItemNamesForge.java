package games.brennan.adventureitemnames.forge;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.internal.VanillaRegistryPoolSource;
import games.brennan.adventureitemnames.item.RandomChestItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

/**
 * Forge mod entrypoint. Same shape as the NeoForge entrypoint but
 * imports from {@code net.minecraftforge.*} rather than
 * {@code net.neoforged.*}. The "name every rolled item" hook lives in
 * {@code common/}'s {@code LootTableMixin}.
 *
 * <p>Pushes the Forge config dir into {@link ConfigPaths} so the
 * common-module user-config loader knows where to find
 * {@code config/adventureitemnames.json}.</p>
 *
 * <p>Registers the built-in ATLA and Adventure Time data packs on the
 * mod event bus via {@link AddPackFindersEvent}. Forge's API doesn't
 * expose a "default-enabled-but-toggleable" mode — both packs ship as
 * <em>always active</em> at the loader level. Users can still suppress
 * specific pools/chains through the {@code NamingConfig} disable layer
 * (see the user config file).</p>
 */
@Mod("adventureitemnames")
public final class AdventureItemNamesForge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, "adventureitemnames");

    public static final RegistryObject<Item> RANDOM_CHEST =
        ITEMS.register("random_chest", () -> new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.DEFAULT));

    public static final RegistryObject<Item> RANDOM_NAMED_CHEST =
        ITEMS.register("random_named_chest", () -> new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ALWAYS_NAMED));

    public static final RegistryObject<Item> RANDOM_ENCHANTED_CHEST =
        ITEMS.register("random_enchanted_chest", () -> new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ENCHANTED));

    public AdventureItemNamesForge(IEventBus modBus) {
        ConfigPaths.set(FMLPaths.CONFIGDIR.get());
        UserConfigLoader.reload();

        // Register before the first reload fires so the synthetic mc_blocks /
        // mc_items pools appear in the title-screen preview and at world load.
        NameRegistry.registerSyntheticPoolSource("mod/adventureitemnames/mc_names",
            new VanillaRegistryPoolSource());

        ITEMS.register(modBus);
        modBus.addListener(AdventureItemNamesForge::onBuildCreativeTab);

        MinecraftForge.EVENT_BUS.addListener(AdventureItemNamesForge::onAddReloadListeners);
        modBus.addListener(AdventureItemNamesForge::onAddPackFinders);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            games.brennan.adventureitemnames.forge.client.AdventureItemNamesForgeClient.register(modBus);
        }
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(RANDOM_CHEST.get());
            event.accept(RANDOM_NAMED_CHEST.get());
            event.accept(RANDOM_ENCHANTED_CHEST.get());
        }
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(NameRegistry.poolListener());
        event.addListener(NameRegistry.chainListener());
        event.addListener(NameRegistry.selectorListener());
        event.addListener(NameRegistry.configListener());
        event.addListener(NameRegistry.chanceListener());
        event.addListener(NameRegistry.colorListener());
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;

        registerBuiltinPack(event, "mc_names", "Adventure Item Names — Minecraft Pack");
        registerBuiltinPack(event, "wholesome", "Adventure Item Names — Wholesome Pack");
        registerBuiltinPack(event, "discord", "Adventure Item Names — Discord Supporters");
        registerBuiltinPack(event, "atla", "Adventure Item Names — ATLA Pack");
        registerBuiltinPack(event, "adventuretime", "Adventure Item Names — Adventure Time Pack");
        registerBuiltinPack(event, "dungeontrain", "Adventure Item Names — Dungeon Train Pack");
        registerBuiltinPack(event, "rickandmorty", "Adventure Item Names — Rick and Morty Pack");
    }

    /**
     * Resolve the on-disk Path for a built-in pack and register it. Tries the
     * production-jar lookup first ({@code IModFile.findResource} on the
     * adventureitemnames mod file). When that fails (Architectury dev mode
     * keeps {@code common/}'s resources off the forge mod's file root),
     * iterates every loaded mod file (the synthetic generated mod owns
     * common's resources in dev), then falls back to the classpath URL.
     */
    private static void registerBuiltinPack(AddPackFindersEvent event,
                                            String packPath, String displayName) {
        Path resourcePath = resolvePackPath(packPath);
        if (resourcePath == null || !Files.exists(resourcePath)) {
            LOGGER.warn("[AdventureItemNames] built-in pack 'resourcepacks/{}' not found inside mod jar or classpath", packPath);
            return;
        }
        PackLocationInfo location = new PackLocationInfo(
            "mod/adventureitemnames/" + packPath,
            Component.literal(displayName),
            PackSource.BUILT_IN,
            Optional.empty()
        );
        Pack.ResourcesSupplier supplier = new PathPackResources.PathResourcesSupplier(resourcePath);
        PackSelectionConfig selectionConfig = new PackSelectionConfig(
            /* required = */ true,
            Pack.Position.TOP,
            /* fixedPosition = */ false
        );
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.SERVER_DATA, selectionConfig);
        if (pack == null) {
            LOGGER.warn("[AdventureItemNames] Pack.readMetaAndCreate returned null for 'resourcepacks/{}' (path={})", packPath, resourcePath);
            return;
        }
        LOGGER.info("[AdventureItemNames] registered built-in data pack '{}' from {}", packPath, resourcePath);
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    /**
     * Three-stage lookup for {@code resourcepacks/<packPath>}:
     * <ol>
     *   <li>The adventureitemnames mod's own {@code IModFile.findResource}
     *       — works in production jar (the pack is physically inside).</li>
     *   <li>Every other loaded mod's {@code IModFile.findResource} — works
     *       in Architectury dev where common's resources live inside the
     *       synthetic {@code generated_<hash>} mod, not in the forge
     *       mod's declared roots.</li>
     *   <li>Classpath URL — last-ditch fallback if the resource is on the
     *       runtime classpath but not owned by any declared mod file.</li>
     * </ol>
     * Returns null if none resolve.
     */
    private static Path resolvePackPath(String packPath) {
        String relative = "resourcepacks/" + packPath;

        var own = ModList.get().getModFileById("adventureitemnames");
        if (own != null) {
            Path p = own.getFile().findResource(relative);
            if (p != null && Files.exists(p)) {
                LOGGER.info("[AdventureItemNames] found '{}' in adventureitemnames mod file", relative);
                return p;
            }
        }

        for (var info : ModList.get().getModFiles()) {
            var modFile = info.getFile();
            if (modFile == null) continue;
            try {
                Path p = modFile.findResource(relative);
                if (p != null && Files.exists(p)) {
                    LOGGER.info("[AdventureItemNames] found '{}' in mod file {}", relative, modFile.getFileName());
                    return p;
                }
            } catch (Exception ignored) {
                // findResource throws on some synthetic mod files — skip and continue.
            }
        }

        URL url = AdventureItemNamesForge.class.getResource("/" + relative + "/pack.mcmeta");
        if (url == null) return null;
        try {
            URI uri = url.toURI();
            if ("jar".equals(uri.getScheme())) {
                try { FileSystems.newFileSystem(uri, Collections.emptyMap()); }
                catch (Exception ignored) { /* already mounted */ }
            }
            Path mcmeta = Path.of(uri);
            LOGGER.info("[AdventureItemNames] found '{}' via classpath URL {}", relative, url);
            return mcmeta.getParent();
        } catch (URISyntaxException | java.nio.file.FileSystemNotFoundException e) {
            LOGGER.warn("[AdventureItemNames] could not resolve classpath URL {} to a Path: {}", url, e.toString());
            return null;
        }
    }
}
