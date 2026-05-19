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

    @Override
    protected void init() {
        Map<String, PackGrouping.PackView> packs = PackGrouping.snapshot();
        list = new DatapackList(minecraft, width, height - 56 - PreviewPanel.HEIGHT, 32,
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
        gfx.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        // Column headers
        int headerY = 28;
        int x = 16;
        gfx.drawString(font, "Pack", x, headerY, 0xFFA0A0A0, false);
        gfx.drawString(font, "Pools", width - 260, headerY, 0xFFA0A0A0, false);
        gfx.drawString(font, "Entries", width - 190, headerY, 0xFFA0A0A0, false);
        gfx.drawString(font, "Σ weight", width - 110, headerY, 0xFFA0A0A0, false);

        preview.render(gfx, mouseX, mouseY, partial);
    }

    void openPack(PackGrouping.PackView pack) {
        Minecraft.getInstance().setScreen(new PoolListScreen(this, buffer, pack));
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
        public int getRowWidth() { return Math.min(width - 32, 600); }

        @Override
        protected int getScrollbarPosition() { return width - 12; }

        static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final PackGrouping.PackView pack;
            private final Button openButton;

            Entry(PackGrouping.PackView pack, DatapacksListScreen host) {
                this.pack = pack;
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
                openButton.setX(rowLeft + rowWidth - 64);
                openButton.setY(rowTop + 3);

                int textY = rowTop + 8;
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(pack.packId()).withStyle(ChatFormatting.WHITE),
                    rowLeft, textY, 0xFFFFFFFF, false);
                gfx.drawString(Minecraft.getInstance().font,
                    Integer.toString(pack.pools().size()),
                    rowLeft + rowWidth - 264, textY, 0xFFE0E0E0, false);
                gfx.drawString(Minecraft.getInstance().font,
                    Integer.toString(pack.totalEntries()),
                    rowLeft + rowWidth - 194, textY, 0xFFE0E0E0, false);
                gfx.drawString(Minecraft.getInstance().font,
                    String.format("%.3f", pack.titleCombinationsSum()),
                    rowLeft + rowWidth - 114, textY, 0xFFE0E0E0, false);

                openButton.render(gfx, mouseX, mouseY, partial);
                RenderSystem.disableBlend();
            }
        }
    }
}
