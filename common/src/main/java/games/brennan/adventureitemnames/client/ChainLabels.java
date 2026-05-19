package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;

/**
 * Display-name helpers for chain ids. Lifted out of {@code SelectorsScreen}
 * so the per-category sub-menus and {@link ChainPicker} can both reuse
 * them without depending on any specific screen class.
 *
 * <p>Two flavours are provided:
 * <ul>
 *   <li>{@link #formatChainLabel(Optional)} — compact, for narrow dropdown
 *       buttons. Strips the namespace prefix when it's the mod's own and
 *       drops the literal word {@code name} that's used as a filler in
 *       most path components. So {@code weapon_name_short} renders as
 *       {@code wep short}.</li>
 *   <li>{@link #formatChainLabelFull(Optional)} — readable, for popup
 *       picker rows. Keeps the word {@code name}, no abbreviations, just
 *       underscore → space. So {@code weapon_name_short} renders as
 *       {@code weapon name short}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class ChainLabels {

    /**
     * Per-word abbreviations applied after dropping {@code name} and
     * splitting on underscores — squeezes common long tokens into
     * narrow dropdown buttons. Unknown tokens pass through unchanged.
     */
    private static final Map<String, String> WORD_ABBREVS = Map.of(
        "weapon", "wep",
        "material", "mat",
        "element", "elem",
        "combinations", "combos",
        "combination", "combo");

    private ChainLabels() {}

    /**
     * Compact display name for a chain id (for use in the dropdown
     * <em>button</em> — limited horizontal space): drops the
     * {@code adventureitemnames:} namespace prefix, strips the literal
     * word {@code name} that's often used as a filler in path
     * components (so {@code weapon_name_short} reads as
     * {@code wep short}), and replaces remaining underscores with
     * spaces.
     */
    public static String formatChainLabel(Optional<ResourceLocation> chain) {
        if (chain.isEmpty()) return "(none)";
        ResourceLocation rl = chain.get();
        String stripped = rl.getPath()
            .replaceFirst("_name_", "_")
            .replaceFirst("_name$", "")
            .replaceFirst("^name_", "");
        if (stripped.isBlank()) stripped = rl.getPath();
        String[] words = stripped.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(WORD_ABBREVS.getOrDefault(words[i], words[i]));
        }
        return prefixNamespace(rl, sb.toString());
    }

    /**
     * Full display name for a chain id (for use in the popup picker
     * list — plenty of horizontal space, prefer readability): replaces
     * underscores with spaces, keeps the literal word {@code name}, no
     * abbreviations. So {@code weapon_name_short} reads as
     * {@code weapon name short}.
     */
    public static String formatChainLabelFull(Optional<ResourceLocation> chain) {
        if (chain.isEmpty()) return "(none)";
        ResourceLocation rl = chain.get();
        return prefixNamespace(rl, rl.getPath().replace('_', ' '));
    }

    private static String prefixNamespace(ResourceLocation rl, String label) {
        if (!"adventureitemnames".equals(rl.getNamespace())) {
            return rl.getNamespace() + ":" + label;
        }
        return label;
    }
}
