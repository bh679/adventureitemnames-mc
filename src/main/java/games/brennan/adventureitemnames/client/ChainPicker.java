package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Centered modal popup that picks a chain id (or {@code (none)}) for one
 * selector tier. Rendered on top of the host screen with a backdrop
 * dimmer. The host screen forwards {@link #mouseClicked} /
 * {@link #render} / {@link #keyPressed} while the picker is active.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Click an entry → {@link Listener#onPicked} with that chain.</li>
 *   <li>Click outside the panel → {@link Listener#onCancelled}.</li>
 *   <li>Esc → {@link Listener#onCancelled}.</li>
 *   <li>Scroll wheel → scrolls the entry list when it overflows.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class ChainPicker {

    public interface Listener {
        void onPicked(Optional<ResourceLocation> chain);
        void onCancelled();
    }

    private static final int PANEL_W = 180;
    /** Vertical space per chain row inside the panel. */
    private static final int ROW_H = 16;
    private static final int HEADER_H = 18;
    private static final int FOOTER_PAD = 6;
    private static final int SIDE_PAD = 6;

    private final Listener listener;
    private final List<Optional<ResourceLocation>> chains;
    private final Optional<ResourceLocation> current;
    private final String title;
    private final int screenW;
    private final int screenH;
    private final int panelX;
    private final int panelY;
    private final int panelH;
    private final int visibleRows;
    private int scrollOffset = 0;

    public ChainPicker(int screenW, int screenH, String title,
                       List<Optional<ResourceLocation>> chains,
                       Optional<ResourceLocation> current,
                       Listener listener) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.title = title;
        this.chains = chains;
        this.current = current;
        this.listener = listener;

        int maxPanelH = Math.max(80, screenH - 40);
        int wantedH = HEADER_H + chains.size() * ROW_H + FOOTER_PAD;
        this.panelH = Math.min(maxPanelH, wantedH);
        this.visibleRows = Math.max(1, (panelH - HEADER_H - FOOTER_PAD) / ROW_H);
        this.panelX = (screenW - PANEL_W) / 2;
        this.panelY = (screenH - panelH) / 2;

        int currentIdx = indexOf(current);
        if (currentIdx >= visibleRows) {
            scrollOffset = Math.min(chains.size() - visibleRows, currentIdx - visibleRows + 1);
        }
    }

    private int indexOf(Optional<ResourceLocation> target) {
        for (int i = 0; i < chains.size(); i++) {
            if (chains.get(i).equals(target)) return i;
        }
        return -1;
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.fill(0, 0, screenW, screenH, 0xA0000000);
        gfx.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xFF1A1A1A);
        gfx.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY,             0xFF707070);
        gfx.fill(panelX - 1, panelY + panelH, panelX + PANEL_W + 1, panelY + panelH + 1, 0xFF707070);
        gfx.fill(panelX - 1, panelY, panelX, panelY + panelH,                       0xFF707070);
        gfx.fill(panelX + PANEL_W, panelY, panelX + PANEL_W + 1, panelY + panelH,   0xFF707070);

        gfx.drawCenteredString(Minecraft.getInstance().font,
            Component.literal(title), panelX + PANEL_W / 2, panelY + 5, 0xFFFFFFFF);

        int rowsStart = panelY + HEADER_H;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= chains.size()) break;
            int rowY = rowsStart + i * ROW_H;
            Optional<ResourceLocation> entry = chains.get(idx);
            boolean hover = mouseX >= panelX + SIDE_PAD
                && mouseX < panelX + PANEL_W - SIDE_PAD
                && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean isCurrent = entry.equals(current);
            int bg = hover ? 0x40FFFFFF : (isCurrent ? 0x30FFCC55 : 0);
            if (bg != 0) {
                gfx.fill(panelX + SIDE_PAD - 1, rowY, panelX + PANEL_W - SIDE_PAD + 1, rowY + ROW_H, bg);
            }
            String label = SelectorsScreen.formatChainLabelFull(entry);
            int textColour = isCurrent ? 0xFFFFCC55 : (hover ? 0xFFFFFFFF : 0xFFD0D0D0);
            gfx.drawString(Minecraft.getInstance().font,
                Component.literal(label),
                panelX + SIDE_PAD + 4, rowY + 4, textColour, false);
        }

        if (chains.size() > visibleRows) {
            int barX = panelX + PANEL_W - 4;
            int barTop = rowsStart;
            int barBot = rowsStart + visibleRows * ROW_H;
            int trackH = barBot - barTop;
            float frac = (float) visibleRows / chains.size();
            int thumbH = Math.max(8, (int) (trackH * frac));
            float scrollFrac = chains.size() <= visibleRows ? 0f
                : (float) scrollOffset / (chains.size() - visibleRows);
            int thumbY = barTop + (int) ((trackH - thumbH) * scrollFrac);
            gfx.fill(barX, barTop, barX + 2, barBot, 0xFF303030);
            gfx.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFA0A0A0);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        if (!inPanel(mouseX, mouseY)) {
            listener.onCancelled();
            return true;
        }
        int rowsStart = panelY + HEADER_H;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= chains.size()) break;
            int rowY = rowsStart + i * ROW_H;
            if (mouseX >= panelX + SIDE_PAD && mouseX < panelX + PANEL_W - SIDE_PAD
                && mouseY >= rowY && mouseY < rowY + ROW_H) {
                listener.onPicked(chains.get(idx));
                return true;
            }
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (chains.size() <= visibleRows) return false;
        int delta = scrollY > 0 ? -1 : 1;
        int newOffset = scrollOffset + delta;
        if (newOffset < 0) newOffset = 0;
        if (newOffset > chains.size() - visibleRows) newOffset = chains.size() - visibleRows;
        scrollOffset = newOffset;
        return true;
    }

    public boolean keyPressed(int keyCode) {
        // GLFW_KEY_ESCAPE = 256
        if (keyCode == 256) {
            listener.onCancelled();
            return true;
        }
        return false;
    }

    private boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + PANEL_W
            && mouseY >= panelY && mouseY < panelY + panelH;
    }
}
