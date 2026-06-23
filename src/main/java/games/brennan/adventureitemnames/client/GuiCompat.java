package games.brennan.adventureitemnames.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Version-bridging helpers for the in-game editor GUI. MC 1.21 changed several
 * client-widget APIs the editor screens rely on; the differences that suit a
 * helper (rather than a per-call-site conditional) are concentrated here so the
 * screens stay readable. Signature-level changes that can't be wrapped — the
 * {@code ContainerObjectSelectionList} super-constructor and {@code mouseScrolled}
 * overrides — are bridged inline at their call sites instead.
 */
public final class GuiCompat {

    private GuiCompat() {}

    /** Callback for {@link #checkbox} — 1.21's {@code Checkbox.OnValueChange} narrowed to the value. */
    @FunctionalInterface
    public interface OnValueChange {
        void onValueChange(boolean value);
    }

    /**
     * Draw the dirt/blur screen background. 1.21's {@code renderBackground} takes the
     * mouse + partial-tick (for the blur shader); 1.20.1's takes only the graphics.
     */
    public static void renderBackground(Screen screen, GuiGraphics graphics,
                                        int mouseX, int mouseY, float partialTick) {
        //? if >=1.21.1 {
        screen.renderBackground(graphics, mouseX, mouseY, partialTick);
        //?} else {
        /*screen.renderBackground(graphics);
        *///?}
    }

    /**
     * Build a (labelless) checkbox wired to {@code onChange}. 1.21 uses the
     * {@code Checkbox.builder(...).onValueChange(...)} API; 1.20.1 has no builder or
     * value callback, so we subclass {@link Checkbox} and fire the callback from
     * {@code onPress()}.
     */
    public static Checkbox checkbox(int x, int y, Component message, Font font,
                                    boolean selected, OnValueChange onChange) {
        //? if >=1.21.1 {
        return Checkbox.builder(message, font)
            .pos(x, y)
            .selected(selected)
            .onValueChange((box, value) -> onChange.onValueChange(value))
            .build();
        //?} else {
        /*return new Checkbox(x, y, 20, 20, message, selected) {
            @Override
            public void onPress() {
                super.onPress();
                onChange.onValueChange(selected());
            }
        };
        *///?}
    }
}
