package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamingConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code <configDir>/adventureitemnames.json} and applies it to the
 * user layer of {@link NamingConfig}. Called once at init by each loader
 * entry-point, and again on every {@code /reload} from
 * {@link ConfigListener}.
 *
 * <p>If the file is absent, the user layer is reset to empty (i.e. no
 * user-level disables). If the file is malformed, the parse errors are
 * logged and the user layer is also reset to empty so the previous
 * config doesn't linger.</p>
 */
public final class UserConfigLoader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "adventureitemnames.json";

    private UserConfigLoader() {}

    /** Re-read the user config file. Safe to call from any thread. */
    public static void reload() {
        Path configDir = ConfigPaths.get();
        if (configDir == null) {
            LOGGER.warn("[AdventureItemNames] config dir not set — skipping user config load");
            NamingConfig.setUserLayer(new DisableSet());
            return;
        }
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            NamingConfig.setUserLayer(new DisableSet());
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            DisableSet ds = ConfigCodec.parse(in, "user(" + file.getFileName() + ")");
            NamingConfig.setUserLayer(ds);
            LOGGER.info("[AdventureItemNames] user config loaded from {}", file);
        } catch (IOException ex) {
            LOGGER.warn("[AdventureItemNames] failed to read {}: {}", file, ex.getMessage());
            NamingConfig.setUserLayer(new DisableSet());
        }
    }
}
