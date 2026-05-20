package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.network.chat.Component;

/**
 * Centered modal confirmation popup with a title, a wrapped warning body,
 * and two buttons — Confirm / Cancel. Used for destructive actions that
 * should not be triggered by an accidental click (segment reset, etc.).
 *
 * <p>Host screen lifecycle mirrors {@link ChainPicker} / {@link TagPicker}:
 * the screen owns an {@code active} field, forwards {@code mouseClicked},
 * {@code keyPressed}, and renders this on top of its own background. Esc
 * cancels.
 */
@Environment(EnvType.CLIENT)
public final class ConfirmDialog {

    public interface Listener {
        void onConfirm();
        void onCancel();
    }

    private static final int PANEL_W = 260;
    private static final int SIDE_PAD = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_W = 90;
    private static final int FOOTER_PAD = 12;

    private final String title;
    private final MultiLineLabel body;
    private final String confirmLabel;
    private final Listener listener;
    private final int screenW;
    private final int screenH;
    private final int panelX;
    private final int panelY;
    private final int panelH;

    public ConfirmDialog(int screenW, int screenH, String title, String bodyText,
                         String confirmLabel, Listener listener) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.title = title;
        this.confirmLabel = confirmLabel;
        this.listener = listener;
        this.body = MultiLineLabel.create(Minecraft.getInstance().font,
            Component.literal(bodyText), PANEL_W - SIDE_PAD * 2);

        int titleH = 20;
        int bodyH = body.getLineCount() * 11;
        this.panelH = titleH + bodyH + FOOTER_PAD + BUTTON_H + SIDE_PAD;
        this.panelX = (screenW - PANEL_W) / 2;
        this.panelY = (screenH - panelH) / 2;
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        gfx.fill(0, 0, screenW, screenH, 0xA0000000);
        gfx.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xFF1A1A1A);
        gfx.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY,             0xFFFFAA55);
        gfx.fill(panelX - 1, panelY + panelH, panelX + PANEL_W + 1, panelY + panelH + 1, 0xFFFFAA55);
        gfx.fill(panelX - 1, panelY, panelX, panelY + panelH,                       0xFFFFAA55);
        gfx.fill(panelX + PANEL_W, panelY, panelX + PANEL_W + 1, panelY + panelH,   0xFFFFAA55);

        gfx.drawCenteredString(font,
            Component.literal("⚠  " + title), panelX + PANEL_W / 2, panelY + 6, 0xFFFFCC55);

        body.renderLeftAligned(gfx, panelX + SIDE_PAD, panelY + 22, 11, 0xFFE0E0E0);

        int buttonY = panelY + panelH - BUTTON_H - SIDE_PAD;
        int cancelX = panelX + (PANEL_W / 2 - BUTTON_W) / 2;
        int confirmX = panelX + PANEL_W / 2 + (PANEL_W / 2 - BUTTON_W) / 2;
        drawButton(gfx, font, mouseX, mouseY, "Cancel", cancelX, buttonY, 0xFF3A3A3A, 0xFFE8E8E8);
        drawButton(gfx, font, mouseX, mouseY, confirmLabel, confirmX, buttonY, 0xFF7A3A3A, 0xFFFFE0C0);
    }

    private static void drawButton(GuiGraphics gfx, net.minecraft.client.gui.Font font,
                                   int mouseX, int mouseY, String label, int x, int y, int bg, int fg) {
        boolean hover = mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
        gfx.fill(x, y, x + BUTTON_W, y + BUTTON_H, hover ? (bg | 0x00202020) : bg);
        gfx.fill(x - 1, y - 1, x + BUTTON_W + 1, y, 0xFF707070);
        gfx.fill(x - 1, y + BUTTON_H, x + BUTTON_W + 1, y + BUTTON_H + 1, 0xFF707070);
        gfx.fill(x - 1, y, x, y + BUTTON_H, 0xFF707070);
        gfx.fill(x + BUTTON_W, y, x + BUTTON_W + 1, y + BUTTON_H, 0xFF707070);
        gfx.drawCenteredString(font, Component.literal(label),
            x + BUTTON_W / 2, y + (BUTTON_H - 8) / 2, fg);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        int buttonY = panelY + panelH - BUTTON_H - SIDE_PAD;
        int cancelX = panelX + (PANEL_W / 2 - BUTTON_W) / 2;
        int confirmX = panelX + PANEL_W / 2 + (PANEL_W / 2 - BUTTON_W) / 2;
        if (inRect(mouseX, mouseY, cancelX, buttonY, BUTTON_W, BUTTON_H)) {
            listener.onCancel();
            return true;
        }
        if (inRect(mouseX, mouseY, confirmX, buttonY, BUTTON_W, BUTTON_H)) {
            listener.onConfirm();
            return true;
        }
        // Click outside the panel cancels.
        if (mouseX < panelX || mouseX >= panelX + PANEL_W
            || mouseY < panelY || mouseY >= panelY + panelH) {
            listener.onCancel();
        }
        return true;
    }

    public boolean keyPressed(int keyCode) {
        // Esc = cancel, Enter = confirm.
        if (keyCode == 256) { listener.onCancel(); return true; }
        if (keyCode == 257 || keyCode == 335) { listener.onConfirm(); return true; }
        return false;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
