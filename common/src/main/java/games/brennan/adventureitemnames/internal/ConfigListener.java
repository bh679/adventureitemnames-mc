package games.brennan.adventureitemnames.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Datapack-layer reload listener for the enable/disable config. Scans
 * {@code data/<any-ns>/disabled/*.json} on every {@code /reload}, unions
 * every file's disable rules into a single {@link DisableSet}, and
 * installs it on {@link NamingConfig} as the datapack layer.
 *
 * <p>Multiple datapacks can each contribute their own file under their
 * own namespace; everything merges. The mod itself ships an empty
 * {@code data/adventureitemnames/disabled/defaults.json}.
 *
 * <p>Reloading the datapack layer also triggers
 * {@link UserConfigLoader#reload()} so server admins can edit
 * {@code <configDir>/adventureitemnames.json} and pick up the change
 * with a single {@code /reload}.
 */
public final class ConfigListener extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public ConfigListener() {
        super(GSON, "disabled");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
        DisableSet merged = new DisableSet();
        for (Map.Entry<ResourceLocation, JsonElement> e : objects.entrySet()) {
            DisableSet fileSet = ConfigCodec.parse(e.getValue(), e.getKey().toString());
            merged.mergeFrom(fileSet);
        }
        NamingConfig.setDatapackLayer(merged);
        LOGGER.info("[AdventureItemNames] disabled layer reloaded — {} file(s)", objects.size());

        UserConfigLoader.reload();
    }
}
