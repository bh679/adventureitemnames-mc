package games.brennan.adventureitemnames.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Pure list logic for a lore entry that is replaced rather than stacked, such as the
 * "Dyed by" line: re-dyeing an item swaps the previous dyer's line for the new one.
 * Version-agnostic and Minecraft-free so it is unit-testable; {@code ItemNameCompat}
 * feeds it the stack's real lore lines.
 */
public final class LoreReplace {

    private LoreReplace() {}

    /**
     * {@code existing} without the last contiguous run of lines whose text equals
     * {@code previous} (split on newlines), followed by {@code added}. When
     * {@code previous} is blank or not found, {@code existing} is kept whole.
     * Never mutates its inputs.
     */
    public static <T> List<T> replaceLast(List<T> existing, String previous, List<T> added,
                                          Function<T, String> text) {
        List<T> result = new ArrayList<>(existing);
        int start = lastRunStart(existing, previous, text);
        if (start >= 0) {
            int length = previous.split("\n", -1).length;
            result.subList(start, start + length).clear();
        }
        result.addAll(added);
        return List.copyOf(result);
    }

    private static <T> int lastRunStart(List<T> lines, String previous, Function<T, String> text) {
        if (previous == null || previous.isBlank()) return -1;
        String[] wanted = previous.split("\n", -1);
        for (int start = lines.size() - wanted.length; start >= 0; start--) {
            if (runMatches(lines, start, wanted, text)) return start;
        }
        return -1;
    }

    private static <T> boolean runMatches(List<T> lines, int start, String[] wanted, Function<T, String> text) {
        for (int i = 0; i < wanted.length; i++) {
            if (!wanted[i].equals(text.apply(lines.get(start + i)))) return false;
        }
        return true;
    }
}
