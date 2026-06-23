package games.brennan.adventureitemnames.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Datapack-layer reload listener for color overrides. Scans
 * {@code data/<any-ns>/colors/*.json} on every {@code /reload}, unions
 * every file's color keys into a single {@link ColorOverrides}, and
 * installs it on {@link NamingConfig} as the datapack color layer.
 *
 * <p>Schema (all keys optional, values are lowercase {@link ChatFormatting}
 * color names — non-color formats like {@code bold} / {@code italic} /
 * {@code reset} are rejected):
 * <pre>{@code
 * {
 *   "description_plain":     "dark_gray",
 *   "description_enchanted": "dark_gray",
 *   "mob_villager":          "gold"
 * }
 * }</pre>
 *
 * <p>Multi-file behaviour: alphabetical iteration order across namespaces
 * → later files clobber earlier ones for matching keys. Files with no
 * recognised keys are silent no-ops.
 *
 * <p>This is the sibling of {@link ChanceLoader} — same shape, same
 * underscore-key skip convention, same merge semantics — only the value
 * type differs (string color name instead of float).
 */
// MC 26.x made SimpleJsonResourceReloadListener generic over the decoded type and takes a
// Codec + FileToIdConverter instead of a Gson + directory string. We still want raw JsonElement
// per file, so parameterise on JsonElement and pass ExtraCodecs.JSON (a Codec<JsonElement>).
//? if >=26 {
/*public final class ColorLoader extends SimpleJsonResourceReloadListener<com.google.gson.JsonElement> {
*///?} else {
public final class ColorLoader extends SimpleJsonResourceReloadListener {
//?}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public ColorLoader() {
        //? if >=26 {
        /*super(net.minecraft.util.ExtraCodecs.JSON, net.minecraft.resources.FileToIdConverter.json("colors"));
        *///?} else {
        super(GSON, "colors");
        //?}
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager mgr, ProfilerFiller profiler) {
        ColorOverrides merged = new ColorOverrides();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            JsonElement root = entry.getValue();
            if (root == null || !root.isJsonObject()) {
                LOGGER.warn("[AdventureItemNames] color file '{}' root is not a JSON object — skipping", entry.getKey());
                continue;
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                // Skip `_`-prefixed keys (convention: `_comment`, `_version`, etc.)
                // so authors can annotate the file without triggering parse warnings.
                if (e.getKey().startsWith("_")) continue;
                ChanceKind kind = ChanceKind.fromKey(e.getKey());
                if (kind == null) {
                    LOGGER.warn("[AdventureItemNames] color file '{}' unknown key '{}'", entry.getKey(), e.getKey());
                    continue;
                }
                JsonElement v = e.getValue();
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                    LOGGER.warn("[AdventureItemNames] color file '{}' '{}' is not a string — skipping",
                        entry.getKey(), e.getKey());
                    continue;
                }
                String name = v.getAsString();
                ChatFormatting color = ChatFormatting.getByName(name);
                if (color == null || !color.isColor()) {
                    LOGGER.warn("[AdventureItemNames] color file '{}' '{}' = '{}' is not a valid color — skipping",
                        entry.getKey(), e.getKey(), name);
                    continue;
                }
                merged.values.put(kind, color);
            }
        }
        NamingConfig.setDatapackColors(merged);
        LOGGER.info("[AdventureItemNames] color layer reloaded — {} file(s)", objects.size());
    }
}
