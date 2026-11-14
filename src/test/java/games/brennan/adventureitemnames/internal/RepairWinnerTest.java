package games.brennan.adventureitemnames.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link RepairWinner#winningIndex}. The rule
 * under test is <em>left, then higher</em>: the left-most column wins, and
 * the higher row breaks a same-column tie. Indices are row-major over a
 * grid {@code width} wide.
 */
class RepairWinnerTest {

    /** {@code [a, b]} side by side in a 2-wide grid → the left one (index 0). */
    @Test
    void sameRow_picksLeftmost() {
        assertEquals(0, RepairWinner.winningIndex(new boolean[]{true, true}, 2));
    }

    /** {@code [b, a]} stacked in a 1-wide grid → the higher one (index 0). */
    @Test
    void sameColumn_picksHigher() {
        assertEquals(0, RepairWinner.winningIndex(new boolean[]{true, true}, 1));
    }

    /**
     * Diagonal: top-right (idx 1, col 1) vs bottom-left (idx 2, col 0) in a
     * 2x2 grid. "Left, then higher" → the bottom-left wins despite being lower.
     */
    @Test
    void diagonal_leftBeatsHigher() {
        // grid:
        //   . X
        //   X .
        boolean[] grid = {false, true, true, false};
        assertEquals(2, RepairWinner.winningIndex(grid, 2));
    }

    /**
     * Other diagonal: top-left (idx 0, col 0) vs bottom-right (idx 3, col 1).
     * Top-left is both more-left and higher → index 0.
     */
    @Test
    void diagonal_topLeftWins() {
        // grid:
        //   X .
        //   . X
        boolean[] grid = {true, false, false, true};
        assertEquals(0, RepairWinner.winningIndex(grid, 2));
    }

    /** Same column but offset: only the column matters first, then the row. */
    @Test
    void leftColumnWins_evenWhenLowerThanRightColumnItem() {
        // 3-wide grid, row 0:  . . X   (idx 2, col 2, row 0)
        //              row 1:  X . .   (idx 3, col 0, row 1)
        boolean[] grid = {false, false, true, true, false, false};
        assertEquals(3, RepairWinner.winningIndex(grid, 3));
    }

    @Test
    void singleItem_returnsThatIndex() {
        assertEquals(2, RepairWinner.winningIndex(new boolean[]{false, false, true}, 3));
    }

    @Test
    void noItems_returnsMinusOne() {
        assertEquals(-1, RepairWinner.winningIndex(new boolean[]{false, false}, 2));
    }

    @Test
    void nonPositiveWidth_returnsMinusOne() {
        assertEquals(-1, RepairWinner.winningIndex(new boolean[]{true, true}, 0));
    }
}
