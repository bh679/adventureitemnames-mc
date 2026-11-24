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
    private static final int LINE_H = 11;

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
        int rulesH = shipped.chanceIf().isEmpty() ? 0 : (shipped.chanceIf().size() + 1) * LINE_H + 6;
        int perCol = Math.max(1, (footerY - 8 - rulesH - TOP) / ROW_H);
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

    /**
     * A chance rule in words, e.g. "50% when First Name picks discord_people or backers".
     * Read-only here: chance rules live in the chain file.
     */
    static Component describeRule(NameSegment.ChanceRule rule, List<ChainEditorScreen.SegmentInfo> segs) {
        List<String> conds = new ArrayList<>();
        for (NameSegment.Condition c : rule.when()) {
            String label = "Seg " + c.segment();
            for (ChainEditorScreen.SegmentInfo info : segs) {
                if (info.index() == c.segment()) label = info.label();
            }
            if (c.refs().isEmpty()) {
                conds.add(Component.translatable("screen.adventureitemnames.chance_if.appears", label).getString());
            } else {
                List<String> names = new ArrayList<>();
                for (var id : c.refs()) names.add(id.getPath());
                String or = Component.translatable("screen.adventureitemnames.chance_if.or").getString();
                conds.add(Component.translatable("screen.adventureitemnames.chance_if.picks",
                    label, String.join(or, names)).getString());
            }
        }
        String and = Component.translatable("screen.adventureitemnames.chance_if.and").getString();
        String pct = Math.round(rule.chance() * 1000f) / 10f + "%";
        return Component.translatable("screen.adventureitemnames.chance_if.rule", pct, String.join(and, conds));
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
        List<NameSegment.ChanceRule> rules = shipped.chanceIf();
        if (rules.isEmpty()) return;
        int y = height - 28 - 6 - (rules.size() + 1) * LINE_H;
        gfx.drawCenteredString(font, Component.translatable("screen.adventureitemnames.chance_if.header"),
            width / 2, y, 0xFFFFFFFF);
        List<ChainEditorScreen.SegmentInfo> segs = parent.liveSegments();
        for (NameSegment.ChanceRule rule : rules) {
            y += LINE_H;
            gfx.drawCenteredString(font, describeRule(rule, segs), width / 2, y, 0xFFA0A0A0);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

}
