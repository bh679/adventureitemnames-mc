package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.internal.UserConfigWriter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;

/**
 * Shared {@code Save to pack} flush logic for every config screen that
 * mutates the buffer. Each save unpacks the {@link EditBuffer} into the
 * full v1+v2 {@link UserConfigWriter#save} signature, then re-loads the
 * user layer so runtime queries reflect the new state without a {@code /reload}.
 *
 * <p>On success the buffer is cleared and {@code onSuccess} runs (typically
 * a callback that re-disables the Save button and rerolls the preview).
 * On failure the buffer is preserved so the user can retry.
 */
@Environment(EnvType.CLIENT)
public final class ConfigSave {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConfigSave() {}

    /** Flush every pending edit in {@code buffer} to disk. */
    public static boolean commit(EditBuffer buffer, Runnable onSuccess) {
        boolean ok = UserConfigWriter.save(
            buffer.snapshotDisabledPools(),
            buffer.snapshotEnabledPools(),
            buffer.snapshotDisabledSelectors(),
            buffer.snapshotEnabledSelectors(),
            buffer.snapshotWeights(),
            buffer.snapshotEntryOverrides(),
            buffer.snapshotChances(),
            buffer.snapshotSelectorTiers(),
            buffer.snapshotSegmentEdits(),
            buffer.snapshotCustomSelectors(),
            buffer.snapshotRemovedCustomSelectorIds());
        if (ok) {
            UserConfigLoader.reload();
            buffer.clear();
            if (onSuccess != null) onSuccess.run();
            LOGGER.info("[AdventureItemNames] user config saved");
        } else {
            LOGGER.warn("[AdventureItemNames] save failed — pending edits retained");
        }
        return ok;
    }
}
