package games.brennan.adventureitemnames.internal;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps a resource-pack id (as reported by
 * {@code Resource.sourcePackId()}) to the on-disk source-tree directory
 * that owns it. Only meaningful in a Loom dev environment where the
 * mod's source files are unpacked next to the runtime — production
 * users have these files inside the mod jar and cannot write to them.
 *
 * <p>Project-root detection walks up from {@code System.getProperty("user.dir")}
 * looking for a {@code gradle.properties} with {@code mod_id=adventureitemnames}.
 * Loom's {@code runClient} task spawns in {@code <root>/fabric/run/} so
 * the walk is usually two levels. Returns {@code null} (with a one-time
 * warning) when no project root is found — caller must guard against
 * that before attempting a write.
 *
 * <p>Pack-id → relative-path mappings are seeded from the same map
 * {@code PackGrouping.friendlyPackName} uses, so the two stay in sync.
 */
public final class PackPaths {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Path projectRoot;
    private static volatile boolean projectRootResolved;

    /** Pack ids (both Forge {@code mod/<mod>/<sub>} and Fabric {@code <mod>:<sub>} flavours) → relative path under project root. */
    private static final Map<String, String> PACK_ID_TO_PATH;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // Base mod (Fabric registers as "fabric"; Loom dev sometimes as "generated_<hash>").
        m.put("mod/adventureitemnames",    "common/src/main/resources/data/adventureitemnames");
        m.put("fabric",                    "common/src/main/resources/data/adventureitemnames");
        // Themed built-in packs.
        m.put("mod/adventureitemnames/mc_names",       "common/src/main/resources/resourcepacks/mc_names/data/adventureitemnames");
        m.put("adventureitemnames:mc_names",           "common/src/main/resources/resourcepacks/mc_names/data/adventureitemnames");
        m.put("mod/adventureitemnames/wholesome",      "common/src/main/resources/resourcepacks/wholesome/data/adventureitemnames");
        m.put("adventureitemnames:wholesome",          "common/src/main/resources/resourcepacks/wholesome/data/adventureitemnames");
        m.put("mod/adventureitemnames/discord",        "common/src/main/resources/resourcepacks/discord/data/adventureitemnames");
        m.put("adventureitemnames:discord",            "common/src/main/resources/resourcepacks/discord/data/adventureitemnames");
        m.put("mod/adventureitemnames/atla",           "common/src/main/resources/resourcepacks/atla/data/adventureitemnames");
        m.put("adventureitemnames:atla",               "common/src/main/resources/resourcepacks/atla/data/adventureitemnames");
        m.put("mod/adventureitemnames/adventuretime",  "common/src/main/resources/resourcepacks/adventuretime/data/adventureitemnames");
        m.put("adventureitemnames:adventuretime",      "common/src/main/resources/resourcepacks/adventuretime/data/adventureitemnames");
        m.put("mod/adventureitemnames/dungeontrain",   "common/src/main/resources/resourcepacks/dungeontrain/data/adventureitemnames");
        m.put("adventureitemnames:dungeontrain",       "common/src/main/resources/resourcepacks/dungeontrain/data/adventureitemnames");
        m.put("mod/adventureitemnames/rickandmorty",   "common/src/main/resources/resourcepacks/rickandmorty/data/adventureitemnames");
        m.put("adventureitemnames:rickandmorty",       "common/src/main/resources/resourcepacks/rickandmorty/data/adventureitemnames");
        PACK_ID_TO_PATH = m;
    }

    private PackPaths() {}

    /**
     * On-disk {@code data/adventureitemnames/} root for the given pack id,
     * or {@code null} when the pack isn't recognised or the project root
     * couldn't be resolved.
     */
    public static Path dataRootFor(String packId) {
        if (packId == null) return null;
        // Loom dev launcher synthesises this pack id every run; treat it as the base mod.
        if (packId.startsWith("generated_")) packId = "mod/adventureitemnames";

        String rel = PACK_ID_TO_PATH.get(packId);
        if (rel == null) {
            // Try stripping common prefixes for unknown pack ids that still belong to the mod.
            if (packId.startsWith("mod/adventureitemnames")
                || packId.startsWith("adventureitemnames:")) {
                LOGGER.warn("[AdventureItemNames] unrecognised pack id '{}' — falling back to base data dir", packId);
                rel = "common/src/main/resources/data/adventureitemnames";
            } else {
                return null;
            }
        }
        Path root = resolveProjectRoot();
        if (root == null) return null;
        return root.resolve(rel);
    }

    /** {@code data/adventureitemnames/naming/chains/<chainPath>.json} resolved under the given pack. */
    public static Path chainFile(String packId, String chainPath) {
        Path data = dataRootFor(packId);
        if (data == null) return null;
        return data.resolve("naming").resolve("chains").resolve(chainPath + ".json");
    }

    /**
     * {@code data/adventureitemnames/naming/pools/<poolPath>.json} resolved
     * under the given pack. Mirrors {@link #chainFile} — pools live in a
     * sibling subdirectory of the same pack data root.
     */
    public static Path poolFile(String packId, String poolPath) {
        Path data = dataRootFor(packId);
        if (data == null) return null;
        return data.resolve("naming").resolve("pools").resolve(poolPath + ".json");
    }

    /**
     * {@code data/adventureitemnames/naming/selectors/<selectorPath>.json}
     * resolved under the given pack. Used by {@link PackSelectorWriter}
     * for both custom-selector creation and tier-override writes on
     * shipped selectors.
     */
    public static Path selectorFile(String packId, String selectorPath) {
        Path data = dataRootFor(packId);
        if (data == null) return null;
        return data.resolve("naming").resolve("selectors").resolve(selectorPath + ".json");
    }

    /**
     * {@code data/adventureitemnames/disabled/<name>.json} resolved under
     * the given pack. Used by {@link PackDisableWriter} — typically called
     * with {@code "defaults"} as the file name.
     */
    public static Path disableFile(String packId, String name) {
        Path data = dataRootFor(packId);
        if (data == null) return null;
        return data.resolve("disabled").resolve(name + ".json");
    }

    /**
     * {@code data/adventureitemnames/chances/<name>.json} resolved under
     * the given pack. Used by {@link PackChanceWriter} — typically called
     * with {@code "defaults"} as the file name.
     */
    public static Path chanceFile(String packId, String name) {
        Path data = dataRootFor(packId);
        if (data == null) return null;
        return data.resolve("chances").resolve(name + ".json");
    }

    /**
     * {@code data/adventureitemnames/colors/<name>.json} resolved under
     * the given pack. Used by {@link PackColorWriter} — typically called
     * with {@code "defaults"} as the file name.
     */
    public static Path colorFile(String packId, String name) {
        Path data = dataRootFor(packId);
        if (data == null) return null;
        return data.resolve("colors").resolve(name + ".json");
    }

    /**
     * Walk up from the current working directory looking for a {@code gradle.properties}
     * file with {@code mod_id=adventureitemnames}. Caches the result so subsequent
     * lookups are cheap.
     */
    private static Path resolveProjectRoot() {
        if (projectRootResolved) return projectRoot;
        synchronized (PackPaths.class) {
            if (projectRootResolved) return projectRoot;
            Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            for (int i = 0; i < 8 && cur != null; i++) {
                Path gradleProps = cur.resolve("gradle.properties");
                if (Files.exists(gradleProps)) {
                    try {
                        String body = Files.readString(gradleProps);
                        if (body.contains("mod_id=adventureitemnames")) {
                            projectRoot = cur;
                            break;
                        }
                    } catch (Exception ex) {
                        // ignore and keep walking
                    }
                }
                cur = cur.getParent();
            }
            projectRootResolved = true;
            if (projectRoot == null) {
                LOGGER.warn("[AdventureItemNames] could not locate AdventureItemNames project root from CWD {} — datapack-editor writes will be disabled",
                    System.getProperty("user.dir"));
            } else {
                LOGGER.info("[AdventureItemNames] resolved project root for datapack editor: {}", projectRoot);
            }
            return projectRoot;
        }
    }

    /** True when we can resolve the project root — required for any dev-mode write. */
    public static boolean projectRootAvailable() {
        return resolveProjectRoot() != null;
    }

    /**
     * Source-tree directory a brand-new user-created pack would live at —
     * {@code <projectRoot>/common/src/main/resources/resourcepacks/<slug>/}.
     * Returns {@code null} when the project root cannot be resolved
     * (production users, or dev environments where the CWD walk failed).
     * Caller uses this for the dev-mode mirror copy in {@code PackCreator};
     * non-null does NOT imply the folder exists.
     */
    public static Path srcTreePackRoot(String slug) {
        if (slug == null || slug.isEmpty()) return null;
        Path root = resolveProjectRoot();
        if (root == null) return null;
        return root.resolve("common/src/main/resources/resourcepacks").resolve(slug);
    }

    /**
     * Canonical pack ids the {@code + New chain} popup offers as save
     * targets — the base mod plus each themed built-in pack, listed in
     * the order they should appear in the dropdown. Only includes the
     * {@code mod/adventureitemnames[/<sub>]} form (not the Fabric
     * {@code adventureitemnames:<sub>} alias) so the popup never offers
     * two entries that point at the same on-disk directory.
     */
    public static Set<String> knownWritablePackIds() {
        Set<String> out = new LinkedHashSet<>();
        List<String> canonical = List.of(
            "mod/adventureitemnames",
            "mod/adventureitemnames/mc_names",
            "mod/adventureitemnames/wholesome",
            "mod/adventureitemnames/discord",
            "mod/adventureitemnames/atla",
            "mod/adventureitemnames/adventuretime",
            "mod/adventureitemnames/dungeontrain",
            "mod/adventureitemnames/rickandmorty");
        for (String id : canonical) {
            if (PACK_ID_TO_PATH.containsKey(id)) out.add(id);
        }
        return out;
    }

    /**
     * Reduce a pack id to its canonical form so that aliases referring to the
     * same on-disk pack collapse to a single key. Loom's dev launcher
     * synthesises a {@code generated_<hash>} pack id for the base mod every
     * run, and Fabric also exposes the base mod's data under the literal
     * {@code "fabric"} alias — both must collapse to {@code mod/adventureitemnames}
     * or the {@link PerPackSplitter} writes the base chain file twice
     * (with the second write clobbering the first via {@code replace: false}).
     *
     * <p>Themed packs have the same problem one level deeper — Fabric reports
     * them as {@code adventureitemnames:<sub>} while Forge/NeoForge report
     * them as {@code mod/adventureitemnames/<sub>}. Both point at the same
     * on-disk directory, so we fold the Fabric form into the Forge canonical
     * form. Without this, a chain referencing both a synthetic pool (tagged
     * with the canonical form on every loader) and a JSON-loaded pool in the
     * same pack (tagged with the loader-native form) would split into two
     * pack layers and clobber each other's writes on Fabric.
     */
    public static String canonicalize(String packId) {
        if (packId == null) return null;
        if (packId.startsWith("generated_")) return "mod/adventureitemnames";
        if ("fabric".equals(packId)) return "mod/adventureitemnames";
        if (packId.startsWith("adventureitemnames:")) {
            return "mod/adventureitemnames/" + packId.substring("adventureitemnames:".length());
        }
        return packId;
    }
}
