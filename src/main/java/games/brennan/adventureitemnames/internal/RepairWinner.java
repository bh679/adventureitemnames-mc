package games.brennan.adventureitemnames.internal;

/**
 * Pure-math helper for crafting-grid repair: given which cells of a crafting
 * grid hold an item, decide which one "wins" and gives the repaired result its
 * name and lore.
 *
 * <p>Selection rule: <em>left, then higher</em> — the item in the left-most
 * column wins; ties within a column are broken by the higher row. This is the
 * same rule AdventureItemStats uses to keep a repaired item's stats, so the
 * name and the stats always come from the same item. Indices are row-major
 * over a grid {@code width} wide ({@code col = i % width}, {@code row = i / width}).
 *
 * <p>Minecraft-free so the position logic is directly JUnit-testable; the
 * {@code RepairItemRecipeMixin} supplies the occupancy array and does the copy.
 */
public final class RepairWinner {

    private RepairWinner() {}

    /**
     * Pick the winning cell by the <em>left, then higher</em> rule.
     *
     * @param occupied per-cell occupancy, row-major, length {@code width * height}
     * @param width    grid width (columns)
     * @return index of the winning cell, or {@code -1} if {@code width <= 0}
     *         or no cell is occupied
     */
    public static int winningIndex(boolean[] occupied, int width) {
        if (width <= 0) return -1;
        int best = -1;
        int bestCol = Integer.MAX_VALUE;
        int bestRow = Integer.MAX_VALUE;
        for (int i = 0; i < occupied.length; i++) {
            if (!occupied[i]) continue;
            int col = i % width;
            int row = i / width;
            if (col < bestCol || (col == bestCol && row < bestRow)) {
                best = i;
                bestCol = col;
                bestRow = row;
            }
        }
        return best;
    }
}
