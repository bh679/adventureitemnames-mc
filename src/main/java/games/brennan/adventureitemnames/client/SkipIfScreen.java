package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameSegment;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The chain editor's "If…" screen for one segment: one checkbox per other live segment of the
 * chain. A ticked segment firing in a roll skips this one (see {@link NameSegment#skipIf()}).
 * Edits go straight into the {@link EditBuffer}, so the parent's preview and Save pick them up.
 */
@Environment(EnvType.CLIENT)
public final class SkipIfScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int COL_W = 200;
    private static final int TOP = 52;

    private final ChainEditorScreen parent;
    private final int segIdx;
    private final NameSegment shipped;
    private final List<Integer> selected = new ArrayList<>();

    SkipIfScreen(ChainEditorScreen parent, int segIdx, NameSegment shipped, String segLabel) {
        super(Component.translatable("screen.adventureitemnames.skip_if.title", segLabel));
        this.parent = parent;
        this.segIdx = segIdx;
        this.shipped = shipped;
        selected.addAll(parent.buffer().effectiveSegmentSkipIf(parent.chain().id(), segIdx, shipped.skipIf()));
    }

    @Override
    protected void init() {
        List<ChainEditorScreen.SegmentInfo> others = new ArrayList<>();
        for (ChainEditorScreen.SegmentInfo info : parent.liveSegments()) {
            if (info.index() != segIdx) others.add(info);
        }
        int footerY = height - 28;
        int perCol = Math.max(1, (footerY - 8 - TOP) / ROW_H);
        int cols = Math.max(1, (others.size() + perCol - 1) / perCol);
        int left = width / 2 - (cols * COL_W) / 2;
        for (int i = 0; i < others.size(); i++) {
            ChainEditorScreen.SegmentInfo info = others.get(i);
            int x = left + (i / perCol) * COL_W;
            int y = TOP + (i % perCol) * ROW_H;
            addRenderableWidget(GuiCompat.checkbox(x, y, Component.literal(describe(info)), font,
                selected.contains(info.index()), on -> toggle(info.index(), on)));
        }
        addRenderableWidget(Button.builder(
            Component.translatable("screen.adventureitemnames.skip_if.reset"),
            b -> resetToShipped()
        ).bounds(width / 2 - 104, footerY, 100, 20).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.done"),
            b -> onClose()
        ).bounds(width / 2 + 4, footerY, 100, 20).build());
    }

    static String describe(ChainEditorScreen.SegmentInfo info) {
        String chance = info.chance() >= 1f ? "100%" : Math.round(info.chance() * 1000f) / 10f + "%";
        return info.index() + " · " + info.label() + " (" + chance + ")";
    }

    private void toggle(int index, boolean on) {
        if (on && !selected.contains(index)) selected.add(index);
        if (!on) selected.remove(Integer.valueOf(index));
        selected.sort(Integer::compare);
        parent.buffer().setSegmentSkipIf(parent.chain().id(), segIdx, List.copyOf(selected));
        parent.rerollPreview();
    }

    private void resetToShipped() {
        selected.clear();
        selected.addAll(shipped.skipIf());
        parent.buffer().setSegmentSkipIf(parent.chain().id(), segIdx, List.copyOf(selected));
        parent.rerollPreview();
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 16, 0xFFFFFFFF);
        gfx.drawCenteredString(font, Component.translatable("screen.adventureitemnames.skip_if.hint"),
            width / 2, 32, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

}
