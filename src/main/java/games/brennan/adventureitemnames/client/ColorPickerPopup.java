package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Centered modal color picker showing the 16 vanilla {@link ChatFormatting}
 * colors as a 4x4 grid of swatches, plus a {@code (default)} button that
 * clears any override and an {@code Other...} placeholder for the future
 * RGB picker.
 *
 * <p>Host screen lifecycle mirrors {@link ConfirmDialog}: the screen owns
 * an {@code active} field, forwards {@code mouseClicked}, {@code keyPressed},
 * and renders this on top of its own background. Esc cancels.
 */
@Environment(EnvType.CLIENT)
public final class ColorPickerPopup {

    public interface Listener {
        /** Selected color, or {@code null} for the {@code (default)} "no override" choice. */
        void onSelected(ChatFormatting color);
        void onCancel();
    }

    /** 4x4 grid order — visually mirrors the in-game color palette. */
    private static final ChatFormatting[] COLORS = {
        ChatFormatting.BLACK,      ChatFormatting.DARK_BLUE,    ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
        ChatFormatting.DARK_RED,   ChatFormatting.DARK_PURPLE,  ChatFormatting.GOLD,       ChatFormatting.GRAY,
        ChatFormatting.DARK_GRAY,  ChatFormatting.BLUE,         ChatFormatting.GREEN,      ChatFormatting.AQUA,
        ChatFormatting.RED,        ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW,     ChatFormatting.WHITE
    };

    private static final int GRID_COLS = 4;
    private static final int GRID_ROWS = 4;
    private static final int SWATCH_SIZE = 22;
    private static final int SWATCH_GAP = 4;
    private static final int SIDE_PAD = 12;
    private static final int TITLE_H = 18;
    private static final int FOOTER_GAP = 8;
    private static final int FOOTER_BTN_H = 18;
    private static final int FOOTER_BTN_W = 84;
    private static final int FOOTER_BTN_GAP = 8;

    private final Listener listener;
    private final ChatFormatting currentColor;
    private final int screenW;
    private final int screenH;
    private final int panelW;
    private final int panelH;
    private final int panelX;
    private final int panelY;
    private final int gridX;
    private final int gridY;
    private final int footerY;

    public ColorPickerPopup(int screenW, int screenH, ChatFormatting currentColor, Listener listener) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.currentColor = currentColor;
        this.listener = listener;

        int gridW = GRID_COLS * SWATCH_SIZE + (GRID_COLS - 1) * SWATCH_GAP;
        int gridH = GRID_ROWS * SWATCH_SIZE + (GRID_ROWS - 1) * SWATCH_GAP;
        this.panelW = gridW + SIDE_PAD * 2;
        this.panelH = TITLE_H + gridH + FOOTER_GAP + FOOTER_BTN_H + SIDE_PAD;
        this.panelX = (screenW - panelW) / 2;
        this.panelY = (screenH - panelH) / 2;
        this.gridX = panelX + SIDE_PAD;
        this.gridY = panelY + TITLE_H;
        this.footerY = gridY + gridH + FOOTER_GAP;
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        // Dim background.
        gfx.fill(0, 0, screenW, screenH, 0xA0000000);
        // Panel.
        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF1A1A1A);
        // Border (1px orange like ConfirmDialog).
        gfx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY,                 0xFFFFAA55);
        gfx.fill(panelX - 1, panelY + panelH, panelX + panelW + 1, panelY + panelH + 1, 0xFFFFAA55);
        gfx.fill(panelX - 1, panelY, panelX, panelY + panelH,                          0xFFFFAA55);
        gfx.fill(panelX + panelW, panelY, panelX + panelW + 1, panelY + panelH,        0xFFFFAA55);

        gfx.drawCenteredString(font,
            Component.translatable("screen.adventureitemnames.color_picker.title"),
            panelX + panelW / 2, panelY + 5, 0xFFFFCC55);

        // Color swatches grid.
        for (int i = 0; i < COLORS.length; i++) {
            int row = i / GRID_COLS;
            int col = i % GRID_COLS;
            int sx = gridX + col * (SWATCH_SIZE + SWATCH_GAP);
            int sy = gridY + row * (SWATCH_SIZE + SWATCH_GAP);
            ChatFormatting color = COLORS[i];
            int rgb = colorRgb(color);
            boolean hovered = mouseX >= sx && mouseX < sx + SWATCH_SIZE
                && mouseY >= sy && mouseY < sy + SWATCH_SIZE;
            boolean selected = color == currentColor;

            // Swatch fill.
            gfx.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, 0xFF000000 | rgb);
            // Border — thicker / brighter when hovered or selected.
            int borderColor = selected ? 0xFFFFFFFF : (hovered ? 0xFFCCCCCC : 0xFF000000);
            gfx.fill(sx - 1, sy - 1, sx + SWATCH_SIZE + 1, sy,                  borderColor);
            gfx.fill(sx - 1, sy + SWATCH_SIZE, sx + SWATCH_SIZE + 1, sy + SWATCH_SIZE + 1, borderColor);
            gfx.fill(sx - 1, sy, sx, sy + SWATCH_SIZE,                          borderColor);
            gfx.fill(sx + SWATCH_SIZE, sy, sx + SWATCH_SIZE + 1, sy + SWATCH_SIZE, borderColor);
        }

        // Footer: (default) and Other...
        int footerLeftX = panelX + SIDE_PAD;
        int footerRightX = footerLeftX + FOOTER_BTN_W + FOOTER_BTN_GAP;
        drawFooterButton(gfx, font, mouseX, mouseY,
            Component.translatable("screen.adventureitemnames.color_picker.default"),
            footerLeftX, footerY, FOOTER_BTN_W, FOOTER_BTN_H,
            currentColor == null, true);
        drawFooterButton(gfx, font, mouseX, mouseY,
            Component.translatable("screen.adventureitemnames.color_picker.other"),
            footerRightX, footerY, FOOTER_BTN_W, FOOTER_BTN_H,
            false, false);
    }

    private void drawFooterButton(GuiGraphics gfx, Font font, int mouseX, int mouseY,
                                  Component label, int x, int y, int w, int h,
                                  boolean selected, boolean enabled) {
        boolean hovered = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int fill = !enabled ? 0xFF2A2A2A : hovered ? 0xFF4A4A4A : (selected ? 0xFF3A5A3A : 0xFF3A3A3A);
        int border = selected ? 0xFFFFFFFF : (hovered ? 0xFFCCCCCC : 0xFF666666);
        gfx.fill(x, y, x + w, y + h, fill);
        gfx.fill(x, y, x + w, y + 1, border);
        gfx.fill(x, y + h - 1, x + w, y + h, border);
        gfx.fill(x, y, x + 1, y + h, border);
        gfx.fill(x + w - 1, y, x + w, y + h, border);
        int textColor = enabled ? 0xFFE8E8E8 : 0xFF808080;
        gfx.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, textColor);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        // Swatch hits.
        for (int i = 0; i < COLORS.length; i++) {
            int row = i / GRID_COLS;
            int col = i % GRID_COLS;
            int sx = gridX + col * (SWATCH_SIZE + SWATCH_GAP);
            int sy = gridY + row * (SWATCH_SIZE + SWATCH_GAP);
            if (mouseX >= sx && mouseX < sx + SWATCH_SIZE
                && mouseY >= sy && mouseY < sy + SWATCH_SIZE) {
                listener.onSelected(COLORS[i]);
                return true;
            }
        }
        // Footer hits.
        int footerLeftX = panelX + SIDE_PAD;
        int footerRightX = footerLeftX + FOOTER_BTN_W + FOOTER_BTN_GAP;
        if (mouseX >= footerLeftX && mouseX < footerLeftX + FOOTER_BTN_W
            && mouseY >= footerY && mouseY < footerY + FOOTER_BTN_H) {
            listener.onSelected(null);
            return true;
        }
        // Other... is disabled — clicking it does nothing for now.
        if (mouseX >= footerRightX && mouseX < footerRightX + FOOTER_BTN_W
            && mouseY >= footerY && mouseY < footerY + FOOTER_BTN_H) {
            return true;
        }
        // Click outside panel cancels.
        if (mouseX < panelX || mouseX >= panelX + panelW
            || mouseY < panelY || mouseY >= panelY + panelH) {
            listener.onCancel();
            return true;
        }
        return true;
    }

    /** Returns true when the popup handled the key — host should not propagate. */
    public boolean keyPressed(int keyCode) {
        if (keyCode == 256) { // GLFW.GLFW_KEY_ESCAPE
            listener.onCancel();
            return true;
        }
        return false;
    }

    /**
     * RGB integer for a {@link ChatFormatting} color, falling back to white
     * for the unexpected case where {@link ChatFormatting#getColor()} is
     * null (non-color format — shouldn't happen for our 16-entry palette).
     */
    public static int colorRgb(ChatFormatting color) {
        if (color == null) return 0xFFFFFF;
        Integer rgb = color.getColor();
        return rgb != null ? rgb : 0xFFFFFF;
    }
}
