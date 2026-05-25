package games.brennan.adventureitemnames.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Datapack-layer reload listener for chance overrides. Scans
 * {@code data/<any-ns>/chances/*.json} on every {@code /reload}, unions
 * every file's chance keys into a single {@link ChanceOverrides}, and
 * installs it on {@link NamingConfig} as the datapack chance layer.
 *
 * <p>Schema (all keys optional, values in {@code [0, 1]}):
 * <pre>{@code
 * {
 *   "plain":        0.30,
 *   "enchanted":    0.50,
 *   "mob_passive":  0.05,
 *   "mob_villager": 1.00
 * }
 * }</pre>
 *
 * <p>Multi-file behaviour: alphabetical iteration order across namespaces
 * → later files clobber earlier ones for matching keys. Files with no
 * recognised keys are silent no-ops.
 */
public final class ChanceLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public ChanceLoader() {
        super(GSON, "chances");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
        ChanceOverrides merged = new ChanceOverrides();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            JsonElement root = entry.getValue();
            if (root == null || !root.isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] chance file '{}' root is not a JSON object — skipping", entry.getKey());
                continue;
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                // Skip `_`-prefixed keys (convention: `_comment`, `_version`, etc.)
                // so authors can annotate the file without triggering parse warnings.
                if (e.getKey().startsWith("_")) continue;
                ChanceKind kind = ChanceKind.fromKey(e.getKey());
                if (kind == null) {
                    LOGGER.warn("[AdventureItemNames] chance file '{}' unknown key '{}'", entry.getKey(), e.getKey());
                    continue;
                }
                JsonElement v = e.getValue();
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                    LOGGER.warn("[AdventureItemNames] chance file '{}' '{}' is not a number — skipping",
                        entry.getKey(), e.getKey());
                    continue;
                }
                float raw = v.getAsFloat();
                if (raw < 0f || raw > 1f) {
                    LOGGER.warn("[AdventureItemNames] chance file '{}' '{}' = {} out of [0,1] — clamping",
                        entry.getKey(), e.getKey(), raw);
                    raw = Math.max(0f, Math.min(1f, raw));
                }
                merged.values.put(kind, raw);
            }
        }
        NamingConfig.setDatapackChances(merged);
        LOGGER.info("[AdventureItemNames] chance layer reloaded — {} file(s)", objects.size());
    }
}
