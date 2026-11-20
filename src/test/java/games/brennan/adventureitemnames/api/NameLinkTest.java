package games.brennan.adventureitemnames.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NameLink#joins}: a link vowel only goes between two consonants (y counts as a vowel). */
class NameLinkTest {

    @Test
    void joinsBetweenConsonants() {
        assertTrue(NameLink.joins("Bakl", "worth"));   // Bakl + a + worth
        assertTrue(NameLink.joins("Gorst", "berry"));
        assertTrue(NameLink.joins("BANG", "Kle"));      // case-insensitive
    }

    @Test
    void noJoinAfterAVowelOrY() {
        assertFalse(NameLink.joins("Bakle", "worth"));
        assertFalse(NameLink.joins("Banty", "worth"));  // y counts as a vowel
        assertFalse(NameLink.joins("Bakly", "kle"));
    }

    @Test
    void noJoinBeforeAVowelOrY() {
        assertFalse(NameLink.joins("Bang", "ock"));
        assertFalse(NameLink.joins("Bang", "ie"));
        assertFalse(NameLink.joins("Bang", "yuk"));
    }

    @Test
    void noJoinAtTheEdges() {
        assertFalse(NameLink.joins("", "worth"));       // nothing before
        assertFalse(NameLink.joins("Bang", ""));        // nothing after (next segment empty)
        assertFalse(NameLink.joins(null, "worth"));
        assertFalse(NameLink.joins("Bang", null));
    }

    @Test
    void nonLettersNeverJoin() {
        assertFalse(NameLink.joins("Bang ", "worth"));
        assertFalse(NameLink.joins("Bang", "-worth"));
    }
}
