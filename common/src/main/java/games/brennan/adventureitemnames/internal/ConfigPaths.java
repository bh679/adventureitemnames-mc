package games.brennan.adventureitemnames.internal;

import java.nio.file.Path;

/**
 * Loader-injected config directory. Each loader entry-point calls
 * {@link #set(Path)} once at init with its native config-folder Path
 * ({@code FabricLoader.getInstance().getConfigDir()} on Fabric,
 * {@code FMLPaths.CONFIGDIR.get()} on Forge / NeoForge). Common code
 * reads the resolved path via {@link #get()}.
 */
public final class ConfigPaths {
    private static volatile Path configDir;

    private ConfigPaths() {}

    public static void set(Path dir) { configDir = dir; }
    public static Path get() { return configDir; }
}
