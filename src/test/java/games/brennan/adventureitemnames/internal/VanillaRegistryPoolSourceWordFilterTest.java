package games.brennan.adventureitemnames.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link VanillaRegistryPoolSource#keepByWordCount(String)}.
 * No Minecraft bootstrap required — only the static word-count helper is
 * exercised.
 */
class VanillaRegistryPoolSourceWordFilterTest {

    @Test
    void singleWord_kept() {
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("Stone"));
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("Cobblestone"));
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("Glass"));
    }

    @Test
    void twoWords_kept() {
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("Oak Planks"));
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("Crafting Table"));
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("Iron Block"));
    }

    @Test
    void threeWords_dropped() {
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("Dark Oak Planks"));
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("Block of Iron"));
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("Mossy Stone Bricks"));
    }

    @Test
    void fourPlusWords_dropped() {
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("Polished Blackstone Brick Stairs"));
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("Waxed Oxidized Cut Copper Stairs"));
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("Light Gray Stained Glass Pane"));
    }

    @Test
    void extraWhitespace_doesNotInflateCount() {
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("  Oak   Planks  "));
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("\tStone\t"));
        assertFalse(VanillaRegistryPoolSource.keepByWordCount("  Dark   Oak   Planks  "));
    }

    @Test
    void emptyAndWhitespaceOnly_kept() {
        // Callers gate empty strings out before reaching this helper, but the
        // predicate itself should treat zero-word input as "no 3+ words found".
        assertTrue(VanillaRegistryPoolSource.keepByWordCount(""));
        assertTrue(VanillaRegistryPoolSource.keepByWordCount("   "));
    }
}
