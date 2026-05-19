package games.brennan.adventureitemnames.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level Datapacks table. One row per data pack that contributes any
 * pool to the naming registry — including the built-in default and ATLA
 * packs and any third-party datapack the user installed.
 *
 * <p>Columns: Pack id · Pools · Total entries · Σ weight in
 * title_combinations. Clicking a row opens the per-pack pool sub-table.
 *
 * <p>The bottom preview strip rolls names using the buffer's pending
 * state so weight edits made in a sub-screen show through after navigating
 * back.
 */
@Environment(EnvType.CLIENT)
public final class DatapacksListScreen extends Screen {

    private final Screen parent;
    private final EditBuffer buffer;
    private DatapackList list;
    private PreviewPanel preview;

    public DatapacksListScreen(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.datapacks.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    /** Column X anchors are right-edge offsets from the screen width. Shared by header + row. */
    static final int COL_W_OPEN     = 72;
    static final int COL_W_SUM      = 124;
    static final int COL_W_ENTRIES  = 176;
    static final int COL_W_POOLS    = 232;
    private static final int HEADER_Y = 44;
    private static final int LIST_TOP = 58;

    @Override
    protected void init() {
        Map<String, PackGrouping.PackView> packs = PackGrouping.snapshot();
        int listBottom = height - PreviewPanel.HEIGHT - 32;
        list = new DatapackList(minecraft, width, listBottom - LIST_TOP, LIST_TOP,
            new ArrayList<>(packs.values()), this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(width / 2 - 100, height - PreviewPanel.HEIGHT - 26, 200, 20).build());

        preview = new PreviewPanel(buffer, null);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);

        gfx.drawString(font, "Pack",     16,                 HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Pools",    width - COL_W_POOLS,    HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Entries",  width - COL_W_ENTRIES,  HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Σ weight", width - COL_W_SUM,      HEADER_Y, 0xFFA0A0A0, false);

        preview.render(gfx, mouseX, mouseY, partial);
    }

    void openPack(PackGrouping.PackView pack) {
        Minecraft.getInstance().setScreen(new PoolListScreen(this, buffer, pack));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** ContainerObjectSelectionList housing one entry per datapack. */
    static final class DatapackList extends ContainerObjectSelectionList<DatapackList.Entry> {

        private final DatapacksListScreen host;

        DatapackList(Minecraft mc, int width, int height, int top,
                     List<PackGrouping.PackView> packs, DatapacksListScreen host) {
            super(mc, width, height, top, 24);
            this.host = host;
            for (PackGrouping.PackView p : packs) addEntry(new Entry(p, host));
        }

        @Override
        public int getRowWidth() { return width - 32; }

        @Override
        protected int getScrollbarPosition() { return width - 12; }

        static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final PackGrouping.PackView pack;
            private final Button openButton;
            private final int screenWidth;

            Entry(PackGrouping.PackView pack, DatapacksListScreen host) {
                this.pack = pack;
                this.screenWidth = host.width;
                this.openButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.open"),
                    b -> host.openPack(pack)
                ).bounds(0, 0, 60, 18).build();
            }

            @Override
            public List<? extends NarratableEntry> narratables() { return List.of(openButton); }

            @Override
            public List<? extends GuiEventListener> children() { return List.of(openButton); }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                openButton.setX(screenWidth - COL_W_OPEN);
                openButton.setY(rowTop + 3);

                int textY = rowTop + 8;
                int packMaxWidth = (screenWidth - COL_W_POOLS) - 16 - 8;
                String packName = PackGrouping.friendlyPackName(pack.packId());
                String truncated = Minecraft.getInstance().font.plainSubstrByWidth(packName, packMaxWidth);
                if (!truncated.equals(packName) && truncated.length() > 1) {
                    truncated = Minecraft.getInstance().font.plainSubstrByWidth(packName, packMaxWidth - 6) + "…";
                }
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(truncated).withStyle(ChatFormatting.WHITE),
                    16, textY, 0xFFFFFFFF, false);
                gfx.drawString(Minecraft.getInstance().font,
                    Integer.toString(pack.pools().size()),
                    screenWidth - COL_W_POOLS, textY, 0xFFE0E0E0, false);
                gfx.drawString(Minecraft.getInstance().font,
                    Integer.toString(pack.totalEntries()),
                    screenWidth - COL_W_ENTRIES, textY, 0xFFE0E0E0, false);
                gfx.drawString(Minecraft.getInstance().font,
                    String.format("%.3f", pack.titleCombinationsSum()),
                    screenWidth - COL_W_SUM, textY, 0xFFE0E0E0, false);

                openButton.render(gfx, mouseX, mouseY, partial);
                RenderSystem.disableBlend();
            }
        }
    }
}
