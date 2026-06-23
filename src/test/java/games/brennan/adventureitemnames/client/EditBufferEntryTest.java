package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.compat.Ids;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.ChanceOverrides;
import games.brennan.adventureitemnames.internal.DisableSet;
import games.brennan.adventureitemnames.internal.EntryOverrides;
import games.brennan.adventureitemnames.internal.SegmentOverrides;
import games.brennan.adventureitemnames.internal.SelectorOverrides;
import games.brennan.adventureitemnames.internal.WeightOverrides;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EditBuffer}'s pool-entry edit routing. Targets
 * the Plan-agent-flagged bug: editing a pending-add must mutate it in
 * place rather than emit a phantom {@code removed:[<text>]} for a text
 * that was never shipped.
 */
class EditBufferEntryTest {

    private static final ResourceLocation POOL =
        Ids.of("adventureitemnames", "colors");

    @AfterEach
    void resetGlobalLayers() {
        NamingConfig.restoreApiLayer(new NamingConfig.ApiSnapshot(
            new DisableSet(), new WeightOverrides(), new EntryOverrides(),
            new ChanceOverrides(), new SelectorOverrides(), new SegmentOverrides()));
        NamingConfig.setUserEntryOverrides(new EntryOverrides());
    }

    private static NamePool poolOf(String... texts) {
        List<NamePool.PoolEntry> entries = new java.util.ArrayList<>();
        for (String t : texts) entries.add(NamePool.PoolEntry.universal(t));
        return new NamePool(POOL, List.copyOf(entries));
    }

    @Test
    void addStagesPendingEntry() {
        EditBuffer buf = new EditBuffer();
        buf.addEntry(POOL, NamePool.PoolEntry.universal("Magenta"));
        EntryOverrides snap = buf.snapshotEntryOverrides();
        assertEquals(1, snap.added.get(POOL).size());
        assertEquals("Magenta", snap.added.get(POOL).get(0).text());
        assertTrue(snap.removed.isEmpty());
        assertTrue(buf.isDirty());
    }

    @Test
    void removeShippedStagesRemoval() {
        EditBuffer buf = new EditBuffer();
        buf.removeShippedEntry(POOL, "Red");
        EntryOverrides snap = buf.snapshotEntryOverrides();
        assertTrue(snap.removed.get(POOL).contains("Red"));
        assertTrue(buf.isDirty());
    }

    @Test
    void editShippedTextStagesRemovePlusAdd() {
        EditBuffer buf = new EditBuffer();
        buf.editEntryText(POOL, "Red", "Crimson", true);
        EntryOverrides snap = buf.snapshotEntryOverrides();
        assertTrue(snap.removed.get(POOL).contains("Red"));
        assertEquals("Crimson", snap.added.get(POOL).get(0).text());
    }

    /**
     * Plan-agent flagged bug: user adds "Magenta", then edits it to
     * "Sunset". Must NOT generate {@code removed:["Magenta"]} — Magenta
     * was never a shipped entry, so removing it is a meaningless line
     * in the saved config. The pending-add should mutate in place.
     */
    @Test
    void editPendingAddMutatesInPlaceNoPhantomRemove() {
        EditBuffer buf = new EditBuffer();
        buf.addEntry(POOL, NamePool.PoolEntry.universal("Magenta"));
        buf.editEntryText(POOL, "Magenta", "Sunset", false);
        EntryOverrides snap = buf.snapshotEntryOverrides();
        assertNull(snap.removed.get(POOL), "no phantom remove for never-shipped text");
        assertEquals(1, snap.added.get(POOL).size());
        assertEquals("Sunset", snap.added.get(POOL).get(0).text());
    }

    /**
     * Second-edit-on-a-shipped-row: caller passes {@code wasShipped=false}
     * the second time because the buffer's pending-add has the current
     * text. EditBuffer mutates the add in place, the original
     * {@code removed:[Red]} survives.
     */
    @Test
    void doubleEditShippedRowRetainsOriginalRemove() {
        EditBuffer buf = new EditBuffer();
        // First edit: shipped "Red" → "Crimson"
        buf.editEntryText(POOL, "Red", "Crimson", true);
        // Second edit: "Crimson" → "Scarlet" — currentlyABufferAdd is true
        buf.editEntryText(POOL, "Crimson", "Scarlet", false);
        EntryOverrides snap = buf.snapshotEntryOverrides();
        assertTrue(snap.removed.get(POOL).contains("Red"));
        assertFalse(snap.removed.get(POOL).contains("Crimson"),
            "Crimson was never shipped — must not appear in removed[]");
        assertEquals(1, snap.added.get(POOL).size());
        assertEquals("Scarlet", snap.added.get(POOL).get(0).text());
    }

    @Test
    void deleteThenReAddUnstagesRemove() {
        EditBuffer buf = new EditBuffer();
        buf.removeShippedEntry(POOL, "Red");
        // User re-adds the same text via the Add box.
        buf.addEntry(POOL, NamePool.PoolEntry.universal("Red"));
        EntryOverrides snap = buf.snapshotEntryOverrides();
        assertNull(snap.removed.get(POOL), "remove should be unstaged");
        // The added side should also stay empty (the entry returned to its shipped state).
        assertTrue(snap.added.isEmpty() || snap.added.get(POOL) == null,
            "no add either — net change is zero");
    }

    @Test
    void unstageAddDropsPendingAdd() {
        EditBuffer buf = new EditBuffer();
        buf.addEntry(POOL, NamePool.PoolEntry.universal("Magenta"));
        buf.unstageAdd(POOL, "Magenta");
        assertFalse(buf.isDirty());
    }

    @Test
    void effectivePoolEntriesOverlayShipped() {
        EditBuffer buf = new EditBuffer();
        NamePool pool = poolOf("Red", "Green", "Blue");
        // No buffer edits → returns shipped directly.
        assertEquals(3, buf.effectiveEntryCount(pool));

        buf.removeShippedEntry(POOL, "Green");
        buf.addEntry(POOL, NamePool.PoolEntry.universal("Magenta"));
        List<NamePool.PoolEntry> eff = buf.effectivePoolEntries(pool);
        assertEquals(3, eff.size()); // Red, Blue, Magenta
        assertEquals("Red", eff.get(0).text());
        assertEquals("Blue", eff.get(1).text());
        assertEquals("Magenta", eff.get(2).text());
    }

    @Test
    void clearDropsAllEntryBuffers() {
        EditBuffer buf = new EditBuffer();
        buf.addEntry(POOL, NamePool.PoolEntry.universal("Magenta"));
        buf.removeShippedEntry(POOL, "Red");
        buf.clear();
        assertFalse(buf.isDirty());
        assertTrue(buf.snapshotEntryOverrides().isEmpty());
    }
}
