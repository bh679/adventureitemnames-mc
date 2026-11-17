package games.brennan.adventureitemnames.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoreReplaceTest {

    private static final Function<String, String> TEXT = Function.identity();

    @Test
    void firstDyeAppendsBelowCraftedLine() {
        List<String> result = LoreReplace.replaceLast(
            List.of("Crafted by Alex"), "", List.of("Dyed by Sam"), TEXT);
        assertEquals(List.of("Crafted by Alex", "Dyed by Sam"), result);
    }

    @Test
    void reDyeReplacesPreviousDyedLineAndKeepsCraftedLine() {
        List<String> result = LoreReplace.replaceLast(
            List.of("Crafted by Alex", "Dyed by Sam"), "Dyed by Sam", List.of("Dyed by Alex"), TEXT);
        assertEquals(List.of("Crafted by Alex", "Dyed by Alex"), result);
    }

    @Test
    void replacesOnlyTheLastMatchingRun() {
        List<String> result = LoreReplace.replaceLast(
            List.of("Dyed by Sam", "Some lore", "Dyed by Sam"), "Dyed by Sam", List.of("Dyed by Kim"), TEXT);
        assertEquals(List.of("Dyed by Sam", "Some lore", "Dyed by Kim"), result);
    }

    @Test
    void multiLinePreviousEntryIsRemovedWhole() {
        List<String> result = LoreReplace.replaceLast(
            List.of("Crafted by Alex", "Dyed by Sam", "with care"), "Dyed by Sam\nwith care",
            List.of("Dyed by Kim"), TEXT);
        assertEquals(List.of("Crafted by Alex", "Dyed by Kim"), result);
    }

    @Test
    void previousLineMissingKeepsAllLore() {
        List<String> result = LoreReplace.replaceLast(
            List.of("Crafted by Alex"), "Dyed by Sam", List.of("Dyed by Kim"), TEXT);
        assertEquals(List.of("Crafted by Alex", "Dyed by Kim"), result);
    }

    @Test
    void inputsAreNotMutated() {
        List<String> existing = new java.util.ArrayList<>(List.of("Crafted by Alex", "Dyed by Sam"));
        LoreReplace.replaceLast(existing, "Dyed by Sam", List.of("Dyed by Kim"), TEXT);
        assertEquals(List.of("Crafted by Alex", "Dyed by Sam"), existing);
    }
}
