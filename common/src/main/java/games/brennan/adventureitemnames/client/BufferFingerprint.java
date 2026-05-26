package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Stable string snapshot of every mutable field on an {@link EditBuffer}.
 * Each config screen captures one of these in {@code init()} and compares
 * it to a fresh fingerprint in {@code onClose()} to decide whether any
 * meaningful change happened during that screen's lifetime — i.e.
 * whether to show the "Unsaved changes" prompt.
 *
 * <p>Per-screen scoping (vs. a global {@code buffer.isDirty()} check)
 * solves the "wrong screen prompt" problem: if you edit weights on
 * RefEditor and then navigate through other screens that don't touch
 * anything, only RefEditor (and the root {@link ConfigScreen} as a
 * safety net) should prompt — intermediate screens see their open and
 * close fingerprints match, and pass through silently.
 *
 * <p>The fingerprint is just a concatenation of every snapshot map's
 * default {@code toString()}. Map iteration order matters: every
 * collection backing a snapshot is insertion-ordered (or a sorted
 * tree), so the same set of edits always produces the same string.
 * Not a hash — kept readable for debug logs.
 */
@Environment(EnvType.CLIENT)
public final class BufferFingerprint {

    private BufferFingerprint() {}

    /** Concatenate every snapshot field of {@code b} into one comparable string. */
    public static String of(EditBuffer b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(512);
        sb.append("w=").append(b.snapshotWeights()).append('\n');
        sb.append("dp=").append(b.snapshotDisabledPools()).append('\n');
        sb.append("ep=").append(b.snapshotEnabledPools()).append('\n');
        sb.append("ds=").append(b.snapshotDisabledSelectors()).append('\n');
        sb.append("es=").append(b.snapshotEnabledSelectors()).append('\n');
        sb.append("c=").append(b.snapshotChances()).append('\n');
        sb.append("co=").append(b.snapshotColors()).append('\n');
        sb.append("st=").append(b.snapshotSelectorTiers()).append('\n');
        sb.append("se=").append(b.snapshotSegmentEdits()).append('\n');
        sb.append("sr=").append(b.snapshotSegmentResets()).append('\n');
        sb.append("as=").append(b.snapshotAppendedSegments()).append('\n');
        sb.append("so=").append(b.snapshotSegmentOrder()).append('\n');
        sb.append("cs=").append(b.snapshotCustomSelectors()).append('\n');
        sb.append("rcs=").append(b.snapshotRemovedCustomSelectorIds()).append('\n');
        sb.append("nc=").append(b.snapshotPendingNewChains()).append('\n');
        var eo = b.snapshotEntryOverrides();
        sb.append("eo+=").append(eo.added).append("\n");
        sb.append("eo-=").append(eo.removed).append("\n");
        return sb.toString();
    }
}
