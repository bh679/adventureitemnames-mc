package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Centered modal popup that picks one or more ref {@link ResourceLocation}s
 * from the merged chain + pool registry. Each row renders the human-readable
 * ref name plus its source pack as a dim chip, mirroring the inline-tag
 * styling used on the Chains list and Ref editor screens.
 *
 * <p>Two filters narrow the visible list: a search box at the top
 * (matches the ref's <em>name</em> only — namespaces and pack ids are
 * never searched), and a row of clickable pack chips below it (click a
 * chip to narrow the list to that pack — when no chip is active, all
 * packs are shown).
 *
 * <p>Row click toggles selection; a footer {@code Add N} button commits
 * every selected ref in one call. {@code Enter} also commits, {@code Esc}
 * cancels.
 */
@Environment(EnvType.CLIENT)
public final class RefPicker {

    public interface Listener {
        /** Called when the user confirms a non-empty selection. */
        void onPicked(Set<ResourceLocation> refs);
        void onCancelled();
    }

    public enum Kind { CHAIN, POOL }

    public record Entry(ResourceLocation id, Kind kind) {}

    private static final int PANEL_W      = 300;
    private static final int ROW_H        = 14;
    private static final int FILTER_ROW_H = 14;
    private static final int FOOTER_H     = 28;
    private static final int SIDE_PAD     = 6;

    /** Sentinel string used as the "no pack" key for context refs in the filter map. */
    private static final String CONTEXT_KEY = "__context__";

    private final Listener listener;
    private final List<Entry> all;
    private final List<Entry> filtered = new ArrayList<>();
    private final String title;
    private final int screenW;
    private final int screenH;
    private final int panelX;
    private final int panelY;
    private final int panelH;
    private final int headerH;
    private final int visibleRows;
    private int scrollOffset = 0;
    private final EditBox searchBox;
    /** Pack id → friendly name, in stable display order. */
    private final Map<String, String> packsInList = new LinkedHashMap<>();
    /** Packs the user has toggled on. Empty = no filter (show all). */
    private final Set<String> selectedPacks = new LinkedHashSet<>();
    /** Per-pack chip bounds, recomputed on every render. Used for click hit-testing. */
    private final List<ChipRect> chipRects = new ArrayList<>();
    /** Currently-selected refs. Persists across search / pack-filter changes. */
    private final LinkedHashSet<ResourceLocation> selected = new LinkedHashSet<>();

    private record ChipRect(String packKey, int x1, int y1, int x2, int y2) {}

    public RefPicker(int screenW, int screenH, String title, List<Entry> entries, Listener listener) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.title = title;
        this.all = List.copyOf(entries);
        this.filtered.addAll(this.all);
        this.listener = listener;
        collectPacks();

        // Header: title + search + (1-or-2 wrapped rows of pack chips).
        int chipLines = estimateChipLines(PANEL_W - SIDE_PAD * 2);
        this.headerH = 18 + 14 + 4 + chipLines * FILTER_ROW_H + 4;

        int maxPanelH = Math.max(140, screenH - 40);
        int wantedH = headerH + Math.min(filtered.size(), 16) * ROW_H + FOOTER_H;
        this.panelH = Math.min(maxPanelH, Math.max(180, wantedH));
        this.visibleRows = Math.max(1, (panelH - headerH - FOOTER_H) / ROW_H);
        this.panelX = (screenW - PANEL_W) / 2;
        this.panelY = (screenH - panelH) / 2;

        this.searchBox = new EditBox(Minecraft.getInstance().font,
            panelX + SIDE_PAD, panelY + 18, PANEL_W - SIDE_PAD * 2, 14, Component.literal("search"));
        this.searchBox.setHint(Component.literal("filter by name…"));
        this.searchBox.setMaxLength(80);
        this.searchBox.setResponder(this::onSearchChanged);
        this.searchBox.setFocused(true);
    }

    /**
     * Build the ordered pack list seen across entries. Context refs
     * collapse into one synthetic "Context" chip; named packs use the
     * same friendly names as the rest of the GUI.
     */
    private void collectPacks() {
        LinkedHashMap<String, String> distinct = new LinkedHashMap<>();
        for (Entry e : all) {
            String key = packKeyOf(e.id(), e.kind());
            if (distinct.containsKey(key)) continue;
            distinct.put(key, friendlyNameOf(key));
        }
        // Sort by friendly name for a stable, readable chip order.
        distinct.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getValue))
            .forEach(en -> packsInList.put(en.getKey(), en.getValue()));
    }

    private static String packKeyOf(ResourceLocation id, Kind kind) {
        if (id.getPath().startsWith("context/")) return CONTEXT_KEY;
        return kind == Kind.CHAIN ? NameRegistry.packIdOfChain(id) : NameRegistry.packIdOfPool(id);
    }

    private static String friendlyNameOf(String packKey) {
        if (CONTEXT_KEY.equals(packKey)) return "Context";
        return PackGrouping.friendlyPackName(packKey);
    }

    /** Rough estimate of chip line count for header sizing. Recomputed precisely at render time. */
    private int estimateChipLines(int availWidth) {
        var font = Minecraft.getInstance().font;
        int cursor = 0;
        int lines = 1;
        int spacing = 4;
        for (String label : packsInList.values()) {
            int w = font.width(label) + 8;
            if (cursor + w > availWidth) { lines++; cursor = 0; }
            cursor += w + spacing;
        }
        return Math.min(lines, 3);
    }

    /**
     * Human-readable ref name — drops the namespace, replaces underscores
     * with spaces, drops the {@code context/} prefix on virtual refs, and
     * sentence-cases the leading word.
     */
    static String formatRefName(ResourceLocation id) {
        String path = id.getPath();
        if (path.startsWith("context/")) path = path.substring("context/".length());
        String label = path.replace('_', ' ').replace("/", " / ");
        if (label.isEmpty()) return id.toString();
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private void onSearchChanged(String text) {
        scrollOffset = 0;
        rebuildFiltered(text);
    }

    private void rebuildFiltered(String searchText) {
        filtered.clear();
        String needle = searchText == null ? "" : searchText.toLowerCase(Locale.ROOT).trim();
        for (Entry e : all) {
            String packKey = packKeyOf(e.id(), e.kind());
            if (!selectedPacks.isEmpty() && !selectedPacks.contains(packKey)) continue;
            if (!needle.isEmpty()) {
                String name = formatRefName(e.id()).toLowerCase(Locale.ROOT);
                if (!name.contains(needle)) continue;
            }
            filtered.add(e);
        }
        if (scrollOffset > Math.max(0, filtered.size() - visibleRows)) {
            scrollOffset = Math.max(0, filtered.size() - visibleRows);
        }
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        gfx.fill(0, 0, screenW, screenH, 0xA0000000);
        gfx.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xFF1A1A1A);
        gfx.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY,             0xFF707070);
        gfx.fill(panelX - 1, panelY + panelH, panelX + PANEL_W + 1, panelY + panelH + 1, 0xFF707070);
        gfx.fill(panelX - 1, panelY, panelX, panelY + panelH,                       0xFF707070);
        gfx.fill(panelX + PANEL_W, panelY, panelX + PANEL_W + 1, panelY + panelH,   0xFF707070);

        gfx.drawCenteredString(font,
            Component.literal(title), panelX + PANEL_W / 2, panelY + 5, 0xFFFFFFFF);

        searchBox.render(gfx, mouseX, mouseY, 0f);

        // Pack-filter chip row(s) below the search box.
        chipRects.clear();
        int chipsX = panelX + SIDE_PAD;
        int chipsY = panelY + 18 + 14 + 4;
        int chipsRightEdge = panelX + PANEL_W - SIDE_PAD;
        int cursor = chipsX;
        int line = 0;
        int spacing = 4;
        for (var entry : packsInList.entrySet()) {
            String key = entry.getKey();
            String label = entry.getValue();
            int chipPad = 4;
            int chipW = font.width(label) + chipPad * 2;
            if (cursor + chipW > chipsRightEdge) {
                line++;
                cursor = chipsX;
            }
            int chipY = chipsY + line * FILTER_ROW_H;
            int chipX2 = cursor + chipW;
            int chipY2 = chipY + 12;
            boolean active = selectedPacks.contains(key);
            int bg = active ? 0xFF3A3A3A : 0xFF202020;
            int fg = active ? 0xFFE8E8E8 : 0xFF606060;
            boolean hover = mouseX >= cursor && mouseX < chipX2 && mouseY >= chipY && mouseY < chipY2;
            if (hover) bg = active ? 0xFF505050 : 0xFF2A2A2A;
            gfx.fill(cursor, chipY, chipX2, chipY2, bg);
            gfx.drawString(font, Component.literal(label), cursor + chipPad, chipY + 2, fg, false);
            chipRects.add(new ChipRect(key, cursor, chipY, chipX2, chipY2));
            cursor = chipX2 + spacing;
        }

        // Refs list.
        int rowsStart = panelY + headerH;
        int markerSlotW = font.width("✓") + 4;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filtered.size()) break;
            int rowY = rowsStart + i * ROW_H;
            Entry e = filtered.get(idx);
            boolean isSelected = selected.contains(e.id());
            boolean hover = mouseX >= panelX + SIDE_PAD
                && mouseX < panelX + PANEL_W - SIDE_PAD - 4
                && mouseY >= rowY && mouseY < rowY + ROW_H;
            int rowBg = 0;
            if (isSelected) rowBg = hover ? 0x604080FF : 0x404080FF;
            else if (hover) rowBg = 0x40FFFFFF;
            if (rowBg != 0) {
                gfx.fill(panelX + SIDE_PAD - 1, rowY, panelX + PANEL_W - SIDE_PAD + 1, rowY + ROW_H, rowBg);
            }
            int markerX = panelX + SIDE_PAD + 4;
            if (isSelected) {
                gfx.drawString(font, Component.literal("✓"), markerX, rowY + 3, 0xFF80FF80, false);
            }
            int nameX = markerX + markerSlotW;
            String name = formatRefName(e.id());
            int nameW = font.width(name);
            int nameColour = hover ? 0xFFFFFFFF : (e.kind() == Kind.CHAIN ? 0xFFCCD8FF : 0xFFD8FFCC);
            gfx.drawString(font, Component.literal(name), nameX, rowY + 3, nameColour, false);

            String packKey = packKeyOf(e.id(), e.kind());
            String packTag = "[" + packsInList.getOrDefault(packKey, friendlyNameOf(packKey)) + "]";
            int tagX = nameX + nameW + 6;
            int tagAvail = (panelX + PANEL_W - SIDE_PAD - 4) - tagX;
            if (tagAvail > 0) {
                String trimmed = font.plainSubstrByWidth(packTag, tagAvail);
                gfx.drawString(font, Component.literal(trimmed), tagX, rowY + 3, 0xFF808080, false);
            }
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

        // Footer: Cancel (left) + Add N (right).
        int footerY = footerY1();
        renderFooterButton(gfx, cancelButtonX1(), footerY, cancelButtonX2(), footerY2(),
            "Cancel", true, mouseX, mouseY);
        String addLabel = selected.isEmpty() ? "Add" : "Add " + selected.size();
        renderFooterButton(gfx, addButtonX1(), footerY, addButtonX2(), footerY2(),
            addLabel, !selected.isEmpty(), mouseX, mouseY);
    }

    private static void renderFooterButton(GuiGraphics gfx, int x1, int y1, int x2, int y2,
                                           String label, boolean enabled, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        boolean hover = enabled && mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
        int bg = !enabled ? 0xFF202020 : hover ? 0xFF505050 : 0xFF3A3A3A;
        int fg = !enabled ? 0xFF606060 : 0xFFE8E8E8;
        gfx.fill(x1, y1, x2, y2, bg);
        gfx.fill(x1, y1 - 1, x2, y1, 0xFF707070);
        gfx.fill(x1, y2, x2, y2 + 1, 0xFF707070);
        gfx.fill(x1 - 1, y1, x1, y2, 0xFF707070);
        gfx.fill(x2, y1, x2 + 1, y2, 0xFF707070);
        int textW = font.width(label);
        int textX = x1 + (x2 - x1 - textW) / 2;
        int textY = y1 + (y2 - y1 - 9) / 2 + 1;
        gfx.drawString(font, Component.literal(label), textX, textY, fg, false);
    }

    private int cancelButtonX1() { return panelX + SIDE_PAD; }
    private int cancelButtonX2() { return panelX + SIDE_PAD + 60; }
    private int addButtonX1()    { return panelX + PANEL_W - SIDE_PAD - 70; }
    private int addButtonX2()    { return panelX + PANEL_W - SIDE_PAD; }
    private int footerY1()       { return panelY + panelH - FOOTER_H + 4; }
    private int footerY2()       { return panelY + panelH - 6; }

    private boolean inCancelButton(double x, double y) {
        return x >= cancelButtonX1() && x < cancelButtonX2() && y >= footerY1() && y < footerY2();
    }
    private boolean inAddButton(double x, double y) {
        return x >= addButtonX1() && x < addButtonX2() && y >= footerY1() && y < footerY2();
    }

    private void commitSelection() {
        if (selected.isEmpty()) return;
        listener.onPicked(new LinkedHashSet<>(selected));
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        if (searchBox.mouseClicked(mouseX, mouseY, button)) return true;
        if (!inPanel(mouseX, mouseY)) {
            listener.onCancelled();
            return true;
        }
        // Pack chip click toggles hidden state.
        for (ChipRect chip : chipRects) {
            if (mouseX >= chip.x1() && mouseX < chip.x2()
                && mouseY >= chip.y1() && mouseY < chip.y2()) {
                if (!selectedPacks.add(chip.packKey())) selectedPacks.remove(chip.packKey());
                rebuildFiltered(searchBox.getValue());
                return true;
            }
        }
        // Footer buttons take priority over rows.
        if (inCancelButton(mouseX, mouseY)) {
            listener.onCancelled();
            return true;
        }
        if (inAddButton(mouseX, mouseY)) {
            commitSelection();
            return true;
        }
        // Row click → toggle selection.
        int rowsStart = panelY + headerH;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filtered.size()) break;
            int rowY = rowsStart + i * ROW_H;
            if (mouseX >= panelX + SIDE_PAD && mouseX < panelX + PANEL_W - SIDE_PAD
                && mouseY >= rowY && mouseY < rowY + ROW_H) {
                ResourceLocation id = filtered.get(idx).id();
                if (!selected.add(id)) selected.remove(id);
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
        // Enter / numpad Enter → commit current selection.
        if (keyCode == 257 || keyCode == 335) {
            commitSelection();
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
