package games.brennan.adventureitemnames.fabric;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.ConfigPaths;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.internal.VanillaRegistryPoolSource;
import games.brennan.adventureitemnames.item.RandomChestItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fabric mod entrypoint. Wraps each {@link NameRegistry} listener with a
 * Fabric-id identifier and registers it on the server-data resource
 * manager so {@code /reload} picks them up alongside vanilla recipes,
 * advancements, loot tables, and tags.
 *
 * <p>Also pushes the Fabric config dir into {@link ConfigPaths} so the
 * common-module {@link UserConfigLoader} can find
 * {@code config/adventureitemnames.json}, registers the built-in themed
 * data packs (all default-enabled), and registers the {@code random_chest}
 * creative test items.</p>
 */
public final class AdventureItemNamesFabric implements ModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Item RANDOM_CHEST = Registry.register(
        BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "random_chest"),
        new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.DEFAULT)
    );

    public static final Item RANDOM_NAMED_CHEST = Registry.register(
        BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "random_named_chest"),
        new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ALWAYS_NAMED)
    );

    public static final Item RANDOM_ENCHANTED_CHEST = Registry.register(
        BuiltInRegistries.ITEM,
        ResourceLocation.fromNamespaceAndPath("adventureitemnames", "random_enchanted_chest"),
        new RandomChestItem(new Item.Properties(), RandomChestItem.Mode.ENCHANTED)
    );

    @Override
    public void onInitialize() {
        ConfigPaths.set(FabricLoader.getInstance().getConfigDir());
        UserConfigLoader.reload();

        // Register before the first reload fires so the synthetic mc_blocks /
        // mc_items pools appear in the title-screen preview and at world load.
        NameRegistry.registerSyntheticPoolSource("mod/adventureitemnames/mc_names",
            new VanillaRegistryPoolSource());

        ResourceManagerHelper rh = ResourceManagerHelper.get(PackType.SERVER_DATA);
        rh.registerReloadListener(wrap(NameRegistry.poolListener(),     "pools"));
        rh.registerReloadListener(wrap(NameRegistry.chainListener(),    "chains"));
        rh.registerReloadListener(wrap(NameRegistry.selectorListener(), "selectors"));
        rh.registerReloadListener(wrap(NameRegistry.configListener(),   "disabled"));
        rh.registerReloadListener(wrap(NameRegistry.chanceListener(),   "chances"));

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
            .register(entries -> {
                entries.accept(RANDOM_CHEST);
                entries.accept(RANDOM_NAMED_CHEST);
                entries.accept(RANDOM_ENCHANTED_CHEST);
            });

        registerBuiltinPack("mc_names",      "Adventure Item Names — Minecraft Pack");
        registerBuiltinPack("wholesome",     "Adventure Item Names — Wholesome Pack");
        registerBuiltinPack("discord",       "Adventure Item Names — Discord Supporters");
        registerBuiltinPack("atla",          "Adventure Item Names — ATLA Pack");
        registerBuiltinPack("adventuretime", "Adventure Item Names — Adventure Time Pack");
        registerBuiltinPack("dungeontrain",  "Adventure Item Names — Dungeon Train Pack");
    }

    /**
     * Register one built-in resource pack with the mod container that
     * actually owns {@code resourcepacks/<packPath>} on disk.
     *
     * <p>In a production jar, this is always the {@code adventureitemnames}
     * container (shadowJar bundles common's resources in). In Architectury
     * Fabric dev mode, common's resources live under a synthetic
     * {@code generated_<hash>} mod whose hash changes each run — so we
     * iterate every loaded mod and pick the first whose root paths contain
     * the pack folder. Without this scan,
     * {@link ResourceManagerHelper#registerBuiltinResourcePack} silently
     * fails in dev because {@code ModNioResourcePack.create} returns null
     * when the {@code adventureitemnames} container's roots don't include
     * the pack directory.</p>
     */
    private static void registerBuiltinPack(String packPath, String displayName) {
        String resourcePath = "resourcepacks/" + packPath;
        ModContainer owner = findContainerWithResource(resourcePath);
        if (owner == null) {
            LOGGER.warn("[AdventureItemNames] built-in pack '{}' not found on any mod's root paths — skipping registration", resourcePath);
            return;
        }
        boolean ok = ResourceManagerHelper.registerBuiltinResourcePack(
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", packPath),
            owner,
            Component.literal(displayName),
            ResourcePackActivationType.DEFAULT_ENABLED
        );
        if (ok) {
            LOGGER.info("[AdventureItemNames] registered built-in data pack '{}' via container '{}'",
                        packPath, owner.getMetadata().getId());
        } else {
            LOGGER.warn("[AdventureItemNames] registerBuiltinResourcePack returned false for '{}' (container '{}')",
                        packPath, owner.getMetadata().getId());
        }
    }

    private static ModContainer findContainerWithResource(String resourcePath) {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                Path candidate = normalizedRoot.resolve(
                    resourcePath.replace("/", normalizedRoot.getFileSystem().getSeparator())
                ).normalize();
                if (candidate.startsWith(normalizedRoot) && Files.exists(candidate)) {
                    return mod;
                }
            }
        }
        return null;
    }

    private static IdentifiableResourceReloadListener wrap(PreparableReloadListener inner, String id) {
        ResourceLocation fabricId = ResourceLocation.fromNamespaceAndPath("adventureitemnames", id);
        return new IdentifiableResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return fabricId; }
            @Override public List<ResourceLocation> getFabricDependencies() { return List.of(); }
            @Override public String getName() { return fabricId.toString(); }
            @Override public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager mgr,
                                                            ProfilerFiller prep, ProfilerFiller apply,
                                                            Executor prepExec, Executor applyExec) {
                return inner.reload(barrier, mgr, prep, apply, prepExec, applyExec);
            }
        };
    }
}
