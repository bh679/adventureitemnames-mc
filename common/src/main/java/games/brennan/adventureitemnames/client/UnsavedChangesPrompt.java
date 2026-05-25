package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.function.Consumer;

/**
 * Factory for the "you have unsaved changes" 3-button popup shown on
 * screen close. Shared by every config screen that mutates the
 * {@link EditBuffer} so the on-close behaviour stays uniform — pressing
 * ESC or clicking Back never silently discards edits.
 *
 * <p>The popup offers:
 *
 * <ul>
 *   <li><b>Save</b> (confirm) — flushes the buffer via
 *       {@link ConfigSave#commit} and then navigates away.</li>
 *   <li><b>Discard</b> (middle) — clears the buffer without saving and
 *       navigates away.</li>
 *   <li><b>Cancel</b> (left) — dismisses the popup; the user stays on
 *       the screen with edits intact.</li>
 * </ul>
 *
 * <p>Esc cancels (= stay on screen). Enter saves.
 */
@Environment(EnvType.CLIENT)
public final class UnsavedChangesPrompt {

    private UnsavedChangesPrompt() {}

    /**
     * Build the 3-button popup. The caller assigns the returned dialog to
     * the host screen's {@code activeConfirm} field via {@code assign}
     * (so the screen's render and event hooks can show it) and provides a
     * {@code navigateAway} runnable used by Save and Discard to close the
     * screen after the buffer has been processed.
     *
     * <p>{@code dismiss} is invoked by Cancel and by Save/Discard once
     * they're done — it should null out the host screen's
     * {@code activeConfirm} field.
     */
    public static ConfirmDialog forClose(int screenW, int screenH,
                                          EditBuffer buffer,
                                          Runnable navigateAway,
                                          Consumer<ConfirmDialog> assign,
                                          Runnable dismiss) {
        ConfirmDialog dialog = new ConfirmDialog(screenW, screenH,
            "Unsaved changes",
            "You have edits that haven't been saved to the pack yet. "
                + "Save them now, or discard them and exit?",
            "Save",
            "Discard",
            new ConfirmDialog.Listener() {
                @Override public void onConfirm() {
                    dismiss.run();
                    ConfigSave.commit(buffer, navigateAway);
                }
                @Override public void onMiddle() {
                    dismiss.run();
                    buffer.clear();
                    navigateAway.run();
                }
                @Override public void onCancel() {
                    dismiss.run();
                }
            });
        assign.accept(dialog);
        return dialog;
    }
}
