package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Centered modal popup that picks one item-tag {@link ResourceLocation}.
 * Mirrors {@link ChainPicker}'s layout + lifecycle — host screen forwards
 * mouse/scroll/key events while {@link #render} draws the dim backdrop
 * and panel. Differs from {@code ChainPicker} in two ways:
 *
 * <ol>
 *   <li>The list is filtered live by a search EditBox at the top of the
 *       panel — there can be hundreds of vanilla + datapack item tags
 *       and scrolling all of them would be unusable.</li>
 *   <li>There is no {@code (none)} sentinel — a tag is mandatory for the
 *       custom-selector flow. Closing the picker without a pick fires
 *       {@link Listener#onCancelled} instead.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class TagPicker {

    public interface Listener {
        void onPicked(ResourceLocation tag);
        void onCancelled();
    }

    private static final int PANEL_W   = 220;
    private static final int ROW_H     = 14;
    private static final int HEADER_H  = 36;   // title + search box
    private static final int FOOTER_PAD = 6;
    private static final int SIDE_PAD  = 6;

    private final Listener listener;
    private final List<ResourceLocation> allTags;
    private final List<ResourceLocation> filtered = new ArrayList<>();
    private final String title;
    private final int screenW;
    private final int screenH;
    private final int panelX;
    private final int panelY;
    private final int panelH;
    private final int visibleRows;
    private int scrollOffset = 0;
    private final EditBox searchBox;

    public TagPicker(int screenW, int screenH, String title,
                     List<ResourceLocation> tags, Listener listener) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.title = title;
        this.allTags = List.copyOf(tags);
        this.filtered.addAll(this.allTags);
        this.listener = listener;

        int maxPanelH = Math.max(120, screenH - 40);
        int wantedH = HEADER_H + Math.min(filtered.size(), 18) * ROW_H + FOOTER_PAD;
        this.panelH = Math.min(maxPanelH, Math.max(160, wantedH));
        this.visibleRows = Math.max(1, (panelH - HEADER_H - FOOTER_PAD) / ROW_H);
        this.panelX = (screenW - PANEL_W) / 2;
        this.panelY = (screenH - panelH) / 2;

        this.searchBox = new EditBox(Minecraft.getInstance().font,
            panelX + SIDE_PAD, panelY + 18, PANEL_W - SIDE_PAD * 2, 14, Component.literal("search"));
        this.searchBox.setHint(Component.literal("filter…"));
        this.searchBox.setMaxLength(80);
        this.searchBox.setResponder(this::onSearchChanged);
        this.searchBox.setFocused(true);
    }

    private void onSearchChanged(String text) {
        scrollOffset = 0;
        filtered.clear();
        if (text == null || text.isBlank()) {
            filtered.addAll(allTags);
            return;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        for (ResourceLocation rl : allTags) {
            if (rl.toString().toLowerCase(Locale.ROOT).contains(needle)) filtered.add(rl);
        }
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

        searchBox.render(gfx, mouseX, mouseY, 0f);

        int rowsStart = panelY + HEADER_H;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filtered.size()) break;
            int rowY = rowsStart + i * ROW_H;
            boolean hover = mouseX >= panelX + SIDE_PAD
                && mouseX < panelX + PANEL_W - SIDE_PAD
                && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hover) {
                gfx.fill(panelX + SIDE_PAD - 1, rowY, panelX + PANEL_W - SIDE_PAD + 1, rowY + ROW_H, 0x40FFFFFF);
            }
            gfx.drawString(Minecraft.getInstance().font,
                Component.literal("#" + filtered.get(idx).toString()),
                panelX + SIDE_PAD + 4, rowY + 3, hover ? 0xFFFFFFFF : 0xFFD0D0D0, false);
        }

        if (filtered.size() > visibleRows) {
            int barX = panelX + PANEL_W - 4;
            int barTop = rowsStart;
            int barBot = rowsStart + visibleRows * ROW_H;
            int trackH = barBot - barTop;
            float frac = (float) visibleRows / filtered.size();
            int thumbH = Math.max(8, (int) (trackH * frac));
            float scrollFrac = filtered.size() <= visibleRows ? 0f
                : (float) scrollOffset / (filtered.size() - visibleRows);
            int thumbY = barTop + (int) ((trackH - thumbH) * scrollFrac);
            gfx.fill(barX, barTop, barX + 2, barBot, 0xFF303030);
            gfx.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFA0A0A0);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        if (searchBox.mouseClicked(mouseX, mouseY, button)) return true;
        if (!inPanel(mouseX, mouseY)) {
            listener.onCancelled();
            return true;
        }
        int rowsStart = panelY + HEADER_H;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filtered.size()) break;
            int rowY = rowsStart + i * ROW_H;
            if (mouseX >= panelX + SIDE_PAD && mouseX < panelX + PANEL_W - SIDE_PAD
                && mouseY >= rowY && mouseY < rowY + ROW_H) {
                listener.onPicked(filtered.get(idx));
                return true;
            }
        }
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (filtered.size() <= visibleRows) return false;
        int delta = scrollY > 0 ? -1 : 1;
        int newOffset = scrollOffset + delta;
        if (newOffset < 0) newOffset = 0;
        if (newOffset > filtered.size() - visibleRows) newOffset = filtered.size() - visibleRows;
        scrollOffset = newOffset;
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            listener.onCancelled();
            return true;
        }
        return searchBox.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char c, int modifiers) {
        return searchBox.charTyped(c, modifiers);
    }

    private boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + PANEL_W
            && mouseY >= panelY && mouseY < panelY + panelH;
    }
}
