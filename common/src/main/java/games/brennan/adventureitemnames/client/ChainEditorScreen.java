package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * v3 — per-chain segment editor. One row per segment in
 * {@link NameChain#segments()}, exposing the four overridable fields:
 * <ul>
 *   <li><b>chance</b> — slider + 2-decimal text-box + {@code ↺} reset</li>
 *   <li><b>connection</b> — single-line text-box</li>
 *   <li><b>newline</b> — checkbox</li>
 *   <li><b>refs</b> — Refs button → {@link RefEditorScreen}</li>
 * </ul>
 *
 * <p>Reads effective values from {@link EditBuffer} so pending edits show
 * before a save. Edits flow back into the buffer's segment-edit map and
 * surface in the gated {@link PreviewPanel} immediately.
 */
@Environment(EnvType.CLIENT)
public final class ChainEditorScreen extends Screen {

    private static final int LIST_TOP = 32;
    private static final int ENTRY_H = 28;

    private final Screen parent;
    private final EditBuffer buffer;
    private final NameChain chain;
    private SegmentList list;
    private PreviewPanel preview;
    private Button saveButton;

    public ChainEditorScreen(Screen parent, EditBuffer buffer, NameChain chain) {
        super(Component.literal(chain.id().toString()));
        this.parent = parent;
        this.buffer = buffer;
        this.chain = chain;
    }

    @Override
    protected void init() {
        int listBottom = height - PreviewPanel.HEIGHT - 32;
        list = new SegmentList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, chain, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.HEIGHT - 26, 80, 20).build());

        saveButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.save"),
            b -> save()
        ).bounds(width - 88, height - PreviewPanel.HEIGHT - 26, 80, 20).build();
        saveButton.active = buffer.isDirty();
        addRenderableWidget(saveButton);

        preview = new PreviewPanel(buffer, null, true);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        if (saveButton != null) saveButton.active = buffer.isDirty();
        preview.render(gfx, mouseX, mouseY, partial);
    }

    private void save() {
        ConfigSave.commit(buffer, () -> {
            if (saveButton != null) saveButton.active = false;
            if (preview != null) preview.rerollNow();
        });
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

    NameChain chain() { return chain; }

    EditBuffer buffer() { return buffer; }

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    static String formatChance(float v) {
        return String.format("%.2f", clamp01(v));
    }

    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    static Float tryParseChance(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            float v = Float.parseFloat(text.trim());
            if (Float.isNaN(v) || v < 0f || v > 1f) return null;
            return v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static final class SegmentList extends ContainerObjectSelectionList<SegmentList.SegEntry> {

        SegmentList(Minecraft mc, int width, int height, int top, NameChain chain, ChainEditorScreen host) {
            super(mc, width, height, top, ENTRY_H);
            List<NameSegment> segs = chain.segments();
            for (int i = 0; i < segs.size(); i++) addEntry(new SegEntry(i, segs.get(i), host));
        }

        @Override public int getRowWidth() { return width - 16; }
        @Override protected int getScrollbarPosition() { return width - 6; }

        static final class SegEntry extends ContainerObjectSelectionList.Entry<SegEntry> {

            private final int segIdx;
            private final NameSegment shipped;
            private final ChainEditorScreen host;

            private final ChanceSlider slider;
            private final EditBox chanceBox;
            private final Button resetButton;
            private final EditBox connectionBox;
            private final Checkbox newlineBox;
            private final Button refsButton;

            private boolean suppressChanceResponder;
            private boolean suppressConnectionResponder;

            SegEntry(int segIdx, NameSegment shipped, ChainEditorScreen host) {
                this.segIdx = segIdx;
                this.shipped = shipped;
                this.host = host;

                float curChance = host.buffer().effectiveSegmentChance(host.chain().id(), segIdx, shipped.chance());
                String curConn = host.buffer().effectiveSegmentConnection(host.chain().id(), segIdx, shipped.connection());
                boolean curNl = host.buffer().effectiveSegmentNewline(host.chain().id(), segIdx, shipped.newline());

                this.slider = new ChanceSlider(0, 0, 80, 18, curChance, v -> {
                    host.buffer().setSegmentChance(host.chain().id(), segIdx,
                        v == shipped.chance() ? null : v);
                    setChanceBox(formatChance(v));
                    host.rerollPreview();
                });

                this.chanceBox = new EditBox(Minecraft.getInstance().font, 0, 0, 40, 18, Component.literal("c"));
                this.chanceBox.setMaxLength(5);
                this.chanceBox.setValue(formatChance(curChance));
                this.chanceBox.setResponder(text -> {
                    if (suppressChanceResponder) return;
                    Float parsed = tryParseChance(text);
                    if (parsed == null) return;
                    host.buffer().setSegmentChance(host.chain().id(), segIdx,
                        parsed == shipped.chance() ? null : parsed);
                    slider.setSliderValue(parsed);
                    host.rerollPreview();
                });

                this.resetButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.reset"),
                    b -> resetRow()
                ).bounds(0, 0, 18, 18).build();

                this.connectionBox = new EditBox(Minecraft.getInstance().font, 0, 0, 70, 18, Component.literal("conn"));
                this.connectionBox.setMaxLength(40);
                this.connectionBox.setValue(curConn);
                this.connectionBox.setResponder(text -> {
                    if (suppressConnectionResponder) return;
                    String shippedConn = shipped.connection();
                    String value = text == null ? "" : text;
                    if (value.equals(shippedConn)) {
                        host.buffer().setSegmentConnection(host.chain().id(), segIdx, null);
                    } else {
                        host.buffer().setSegmentConnection(host.chain().id(), segIdx, value);
                    }
                    host.rerollPreview();
                });

                this.newlineBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(curNl)
                    .onValueChange((c, v) -> {
                        host.buffer().setSegmentNewline(host.chain().id(), segIdx,
                            v == shipped.newline() ? null : v);
                        host.rerollPreview();
                    })
                    .build();

                this.refsButton = Button.builder(
                    Component.literal("Refs"),
                    b -> Minecraft.getInstance().setScreen(new RefEditorScreen(host, host.buffer(), host.chain(), segIdx, shipped))
                ).bounds(0, 0, 44, 18).build();
            }

            private void resetRow() {
                host.buffer().setSegmentChance(host.chain().id(), segIdx, null);
                host.buffer().setSegmentConnection(host.chain().id(), segIdx, null);
                host.buffer().setSegmentNewline(host.chain().id(), segIdx, null);
                slider.setSliderValue(shipped.chance());
                setChanceBox(formatChance(shipped.chance()));
                setConnectionBox(shipped.connection());
                if (newlineBox.selected() != shipped.newline()) newlineBox.onPress();
                host.rerollPreview();
            }

            private void setChanceBox(String text) {
                suppressChanceResponder = true;
                try { chanceBox.setValue(text); } finally { suppressChanceResponder = false; }
            }

            private void setConnectionBox(String text) {
                suppressConnectionResponder = true;
                try { connectionBox.setValue(text); } finally { suppressConnectionResponder = false; }
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(slider, chanceBox, resetButton, connectionBox, newlineBox, refsButton);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(slider, chanceBox, resetButton, connectionBox, newlineBox, refsButton);
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int x = rowLeft + 4;
                int y = rowTop + (rowHeight - 18) / 2;
                int textY = rowTop + (rowHeight - 9) / 2;

                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal("#" + segIdx), x, textY, 0xFFE0E0E0, false);
                x += 22;

                setW(slider, 80); slider.setX(x); slider.setY(y); slider.render(gfx, mouseX, mouseY, partial); x += 84;
                setW(chanceBox, 40); chanceBox.setX(x); chanceBox.setY(y); chanceBox.render(gfx, mouseX, mouseY, partial); x += 44;
                setW(resetButton, 18); resetButton.setX(x); resetButton.setY(y); resetButton.render(gfx, mouseX, mouseY, partial); x += 22;

                int connAvail = Math.max(40, rowLeft + rowWidth - x - 4 - 18 - 4 - 44 - 6);
                setW(connectionBox, connAvail); connectionBox.setX(x); connectionBox.setY(y); connectionBox.render(gfx, mouseX, mouseY, partial); x += connAvail + 4;

                newlineBox.setX(x); newlineBox.setY(y); newlineBox.render(gfx, mouseX, mouseY, partial); x += 18 + 4;

                refsButton.setX(x); refsButton.setY(y); refsButton.render(gfx, mouseX, mouseY, partial);
            }

            private static void setW(AbstractWidget w, int newWidth) {
                w.setWidth(newWidth);
            }
        }
    }

    /** Slider mapped 1:1 to a float in {@code [0, 1]}, mirroring {@link SpawnChancesScreen}. */
    static final class ChanceSlider extends AbstractSliderButton {

        private final java.util.function.Consumer<Float> onChange;

        ChanceSlider(int x, int y, int w, int h, float initial, java.util.function.Consumer<Float> onChange) {
            super(x, y, w, h, Component.literal(""), clamp01(initial));
            this.onChange = onChange;
            updateMessage();
        }

        void setSliderValue(double newValue) {
            this.value = clamp01((float) newValue);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(formatChance((float) value)));
        }

        @Override
        protected void applyValue() {
            if (onChange != null) onChange.accept((float) value);
        }
    }
}
