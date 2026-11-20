package games.brennan.adventureitemnames.api;

/**
 * The rule for a {@link NameSegment#link() link} segment: a joining fragment
 * (e.g. a vowel between two name endings) only goes in between two consonants,
 * so it never makes a double vowel. a, e, i, o, u and y count as vowels.
 *
 * <p>Pure string logic, so it is tested without a bootstrapped registry.
 */
public final class NameLink {
    private static final String VOWELS = "aeiouy";

    private NameLink() {}

    /**
     * Whether a link may sit between {@code before} (the text so far) and
     * {@code after} (the next segment's fragment): both sides must be present
     * and meet at consonants.
     */
    public static boolean joins(CharSequence before, String after) {
        if (before == null || before.length() == 0 || after == null || after.isEmpty()) return false;
        return isConsonant(before.charAt(before.length() - 1)) && isConsonant(after.charAt(0));
    }

    static boolean isConsonant(char c) {
        return Character.isLetter(c) && VOWELS.indexOf(Character.toLowerCase(c)) < 0;
    }
}
