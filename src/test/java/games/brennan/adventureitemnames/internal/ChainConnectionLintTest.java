package games.brennan.adventureitemnames.internal;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.compat.Ids;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lints every shipped chain JSON (base corpus + bundled resource packs) for
 * spacing bugs caused by {@code NameComposer.composeChain} calling
 * {@code stripLeading()} on every nested fragment:
 * <ul>
 *   <li>A connection ending in a letter glues onto the fragment that follows
 *       ({@code " for"} + "The Princess" → "forThe Princess").</li>
 *   <li>A base ({@code replace: true}) connection-only segment ending in
 *       whitespace emits a dangling word + trailing space ("Crafted with  by X").</li>
 * </ul>
 * Uses the real {@link NameCodec}, so the files are parsed exactly as the
 * reload listener parses them. No Minecraft bootstrap needed.
 */
class ChainConnectionLintTest {

    private static List<Path> chainFiles() throws Exception {
        URL anchor = ChainConnectionLintTest.class.getClassLoader()
            .getResource("data/adventureitemnames/naming/chains/forged_by.json");
        assertNotNull(anchor, "base forged_by.json not on test classpath");
        // .../resources/data/adventureitemnames/naming/chains/forged_by.json → resources root
        Path root = Path.of(anchor.toURI()).getParent().getParent().getParent().getParent().getParent();
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".json"))
                .filter(p -> p.getParent().getFileName().toString().equals("chains")
                    && p.getParent().getParent().getFileName().toString().equals("naming"))
                .toList();
            assertTrue(files.size() > 20, "expected base + pack chains, found " + files.size());
            return files;
        }
    }

    private static NameChain parse(Path p) throws Exception {
        try (InputStream in = Files.newInputStream(p)) {
            return NameCodec.parseChain(in, Ids.of("adventureitemnames", "lint"));
        }
    }

    @Test
    void noConnectionEndsInLetterBeforeRefs() throws Exception {
        List<String> bad = new ArrayList<>();
        for (Path p : chainFiles()) {
            List<NameSegment> segs = parse(p).segments();
            for (int i = 0; i < segs.size(); i++) {
                NameSegment s = segs.get(i);
                String c = s.connection();
                if (!s.refs().isEmpty() && !c.isEmpty() && Character.isLetter(c.charAt(c.length() - 1))) {
                    bad.add(p + " seg" + i + " connection=\"" + c + "\"");
                }
            }
        }
        assertTrue(bad.isEmpty(), "connections glue onto the next fragment:\n" + String.join("\n", bad));
    }

    @Test
    void baseChainsHaveNoDanglingConnectionOnlySegments() throws Exception {
        List<String> bad = new ArrayList<>();
        for (Path p : chainFiles()) {
            NameChain chain = parse(p);
            if (!chain.replace()) continue; // additive pack layers: metadata comes from base
            List<NameSegment> segs = chain.segments();
            for (int i = 0; i < segs.size(); i++) {
                NameSegment s = segs.get(i);
                String c = s.connection();
                if (s.refs().isEmpty() && !c.isBlank() && Character.isWhitespace(c.charAt(c.length() - 1))) {
                    bad.add(p + " seg" + i + " connection=\"" + c + "\"");
                }
            }
        }
        assertTrue(bad.isEmpty(), "connection-only segments emit a dangling word:\n" + String.join("\n", bad));
    }

    @Test
    void wholesomeOverlayStillAddsWithLoveSegment() throws Exception {
        Path base = null, wholesome = null;
        for (Path p : chainFiles()) {
            if (!p.getFileName().toString().equals("hand_crafted_with_love.json")) continue;
            if (p.toString().contains("wholesome")) wholesome = p; else if (!p.toString().contains("resourcepacks")) base = p;
        }
        assertNotNull(base);
        assertNotNull(wholesome);

        NameChain baseChain = parse(base);
        assertEquals(2, baseChain.segments().size(), "base chain should end at 'Crafted'");

        NameChain merged = NameRegistry.mergeLayers(List.of(
            new NameRegistry.ChainLayer(baseChain, "base"),
            new NameRegistry.ChainLayer(parse(wholesome), "wholesome")));
        assertEquals(3, merged.segments().size());
        NameSegment withLove = merged.segments().get(2);
        assertEquals(" with ", withLove.connection());
        assertEquals("With Love", withLove.label());
        assertFalse(withLove.refs().isEmpty(), "wholesome refs must fill the 'with' word");
    }
}
