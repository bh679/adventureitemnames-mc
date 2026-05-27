package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NamingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
    private NameChain chain;
    private SegmentList list;
    private PreviewPanel preview;
    private Button saveButton;
    /** Active confirmation popup (e.g. before reset, or unsaved-changes prompt). {@code null} when no popup is open. */
    private ConfirmDialog activeConfirm;
    private String openFingerprint;
    private boolean deleteMode;

    public ChainEditorScreen(Screen parent, EditBuffer buffer, NameChain chain) {
        super(Component.literal(ChainsListScreen.formatChainName(chain.id())));
        this.parent = parent;
        this.buffer = buffer;
        this.chain = chain;
    }

    @Override
    protected void init() {
        // Re-fetch the chain from the registry so that a Save in a child
        // screen (which updates NameRegistry via putChainInMemory) is
        // visible when control returns here via Back. Without this the
        // SegEntry rows would read shipped.label() / refs() / chance from
        // the pre-save snapshot captured at constructor time.
        var refreshed = games.brennan.adventureitemnames.internal.NameRegistry.chain(chain.id());
        if (refreshed.isPresent()) chain = refreshed.get();
        int listBottom = height - PreviewPanel.currentHeight() - 32;
        list = new SegmentList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, chain, this);
        addRenderableWidget(list);

        int footerY = height - PreviewPanel.currentHeight() - 26;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, footerY, 80, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("+ Add segment"),
            b -> appendNewSegment()
        ).bounds(width / 2 - 60, footerY, 120, 20).build());

        Button deleteToggle = Button.builder(
            Component.literal("🗑").withStyle(deleteMode ? ChatFormatting.RED : ChatFormatting.GRAY),
            b -> toggleDeleteMode()
        ).bounds(width - 88 - 4 - 24, footerY, 24, 20).build();
        deleteToggle.setTooltip(Tooltip.create(
            Component.translatable("screen.adventureitemnames.action.delete_mode_tooltip")));
        addRenderableWidget(deleteToggle);

        saveButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.save"),
            b -> save()
        ).bounds(width - 88, footerY, 80, 20).build();
        saveButton.active = buffer.isDirty();
        addRenderableWidget(saveButton);

        if (preview == null) preview = PreviewPanel.chainOnly(buffer, chain.id(), this::rebuildWidgets);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
        addRenderableWidget(preview.toggleButton());

        if (openFingerprint == null) openFingerprint = BufferFingerprint.of(buffer);
    }

    private void appendNewSegment() {
        NameSegment fresh = new NameSegment(new java.util.ArrayList<>(), 1.0f, " ", false);
        buffer.appendSegment(chain.id(), fresh);
        rebuildWidgets();
        rerollPreview();
    }

    void removeSegment(int effectiveIdx) {
        String segLabel = "Seg " + effectiveIdx;
        activeConfirm = new ConfirmDialog(width, height,
            Component.translatable("screen.adventureitemnames.delete.title", segLabel).getString(),
            Component.translatable("screen.adventureitemnames.delete.segment.body").getString(),
            Component.translatable("screen.adventureitemnames.delete.confirm").getString(),
            new ConfirmDialog.Listener() {
                @Override public void onConfirm() {
                    activeConfirm = null;
                    buffer.setSegmentRemoved(chain.id(), effectiveIdx, true);
                    rebuildWidgets();
                    rerollPreview();
                }
                @Override public void onCancel() { activeConfirm = null; }
            });
    }

    boolean deleteMode() { return deleteMode; }

    private void toggleDeleteMode() {
        deleteMode = !deleteMode;
        rebuildWidgets();
    }

    void moveSegment(int displayPos, int delta) {
        int total = NamingConfig.effectiveSegmentCount(chain.id(), chain.segments().size())
            + buffer.pendingAppendedSegments(chain.id()).size();
        buffer.moveSegment(chain.id(), total, displayPos, delta);
        rebuildWidgets();
        rerollPreview();
    }

    /**
     * Open a confirmation popup for the row's reset button. Reset removes
     * the segment's saved overrides on next save (chance / connection /
     * newline / refs / removed flag all drop). Destructive enough that
     * accidental clicks should be guarded.
     */
    void openResetConfirm(int segIdx, Runnable onConfirm) {
        activeConfirm = new ConfirmDialog(width, height,
            "Reset Seg " + segIdx + "?",
            "This will clear every saved override on this segment "
                + "(chance, connection, newline, refs, removed flag). "
                + "The segment falls back to its shipped behaviour. "
                + "Pending edits for this segment are also discarded. Save to commit.",
            "Reset",
            new ConfirmDialog.Listener() {
                @Override public void onConfirm() { activeConfirm = null; onConfirm.run(); }
                @Override public void onCancel()  { activeConfirm = null; }
            });
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (activeConfirm != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeConfirm.render(gfx, mouseX, mouseY);
            return;
        }
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
        if (activeConfirm != null) { activeConfirm.mouseClicked(mouseX, mouseY, button); return true; }
        if (preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeConfirm != null && activeConfirm.keyPressed(keyCode)) return true;
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (BufferFingerprint.of(buffer).equals(openFingerprint)) {
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        UnsavedChangesPrompt.forClose(width, height, buffer,
            () -> Minecraft.getInstance().setScreen(parent),
            d -> activeConfirm = d,
            () -> activeConfirm = null);
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
            List<NameSegment> shipped = chain.segments();
            int shippedCount = shipped.size();
            int total = NamingConfig.effectiveSegmentCount(chain.id(), shippedCount)
                + host.buffer().pendingAppendedSegments(chain.id()).size();
            // Walk the user's effective display order (buffer pending → user-config → identity).
            List<Integer> order = host.buffer().effectiveSegmentOrder(chain.id(), total);
            int displayPos = 0;
            for (int origIdx : order) {
                if (host.buffer().isSegmentRemovedPending(chain.id(), origIdx)) { displayPos++; continue; }
                NameSegment base = origIdx < shippedCount
                    ? shipped.get(origIdx)
                    : segmentAtEffective(host, chain, origIdx, shippedCount);
                if (base == null) { displayPos++; continue; }
                addEntry(new SegEntry(origIdx, displayPos, total, base, host));
                displayPos++;
            }
        }

        /**
         * Resolve an appended segment by effective index, layering buffer
         * pending → user-config → API. Mirrors the composer's view but
         * inclues the unsaved buffer so brand-new segments appear in the UI.
         */
        private static NameSegment segmentAtEffective(ChainEditorScreen host, NameChain chain, int effIdx, int shippedCount) {
            int rel = effIdx - shippedCount;
            // First: anything appended in this session via the buffer.
            // The buffer's list is the FULL pending list — it doesn't overlay
            // saved appendeds, it sits after them. So saved appendeds come
            // first, buffer appendeds come last.
            var userAppended = NamingConfig.snapshotUserAppendedSegments()
                .getOrDefault(chain.id().toString(), List.of());
            if (rel < userAppended.size()) return userAppended.get(rel);
            int relAfterUser = rel - userAppended.size();
            var bufferAppended = host.buffer().pendingAppendedSegments(chain.id());
            if (relAfterUser < bufferAppended.size()) return bufferAppended.get(relAfterUser);
            return null;
        }

        @Override public int getRowWidth() { return width - 16; }
        @Override protected int getScrollbarPosition() { return width - 6; }

        static final class SegEntry extends ContainerObjectSelectionList.Entry<SegEntry> {

            private final int segIdx;
            /** 0-based position in the display order — also the index passed to moveSegment. */
            private final int displayPos;
            private final int totalCount;
            private final NameSegment shipped;
            private final ChainEditorScreen host;

            private final ChanceSlider slider;
            private final EditBox chanceBox;
            private final Button resetButton;
            private final EditBox connectionBox;
            private final Checkbox newlineBox;
            private final Button refsButton;
            private final Button deleteButton;
            private final Button upButton;
            private final Button downButton;
            private final EditBox labelBox;

            private boolean suppressChanceResponder;
            private boolean suppressConnectionResponder;
            private boolean suppressLabelResponder;

            SegEntry(int segIdx, int displayPos, int totalCount, NameSegment shipped, ChainEditorScreen host) {
                this.segIdx = segIdx;
                this.displayPos = displayPos;
                this.totalCount = totalCount;
                this.shipped = shipped;
                this.host = host;

                float curChance = host.buffer().effectiveSegmentChance(host.chain().id(), segIdx, shipped.chance());
                String curConn = host.buffer().effectiveSegmentConnection(host.chain().id(), segIdx, shipped.connection());
                boolean curNl = host.buffer().effectiveSegmentNewline(host.chain().id(), segIdx, shipped.newline());
                String curLabel = host.buffer().effectiveSegmentLabel(host.chain().id(), segIdx, shipped.label());

                this.labelBox = new EditBox(Minecraft.getInstance().font, 0, 0, 80, 18, Component.literal("label"));
                this.labelBox.setMaxLength(40);
                this.labelBox.setHint(Component.literal("Seg " + segIdx));
                this.labelBox.setValue(curLabel);
                this.labelBox.setResponder(text -> {
                    if (suppressLabelResponder) return;
                    host.buffer().setSegmentLabel(host.chain().id(), segIdx, text == null ? "" : text);
                });

                this.slider = new ChanceSlider(0, 0, 80, 18, curChance, v -> {
                    // Always store the user's value, even when it matches shipped — the
                    // reset (↺) button is the explicit way to clear an override. The
                    // previous "match shipped → null" shortcut silently dropped edits
                    // whenever a user happened to land on the shipped value (common for
                    // segments with non-1.0 shipped chances like 0.05 / 0.5).
                    host.buffer().setSegmentChance(host.chain().id(), segIdx, v);
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
                    host.buffer().setSegmentChance(host.chain().id(), segIdx, parsed);
                    slider.setSliderValue(parsed);
                    host.rerollPreview();
                });

                this.resetButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.reset"),
                    b -> host.openResetConfirm(segIdx, this::resetRow)
                ).bounds(0, 0, 18, 18).build();
                this.resetButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Reset this segment's chance / connection / newline overrides")));

                this.connectionBox = new EditBox(Minecraft.getInstance().font, 0, 0, 70, 18, Component.literal("conn"));
                this.connectionBox.setMaxLength(40);
                this.connectionBox.setValue(curConn);
                this.connectionBox.setResponder(text -> {
                    if (suppressConnectionResponder) return;
                    String value = text == null ? "" : text;
                    host.buffer().setSegmentConnection(host.chain().id(), segIdx, value);
                    host.rerollPreview();
                });

                this.newlineBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(curNl)
                    .onValueChange((c, v) -> {
                        host.buffer().setSegmentNewline(host.chain().id(), segIdx, v);
                        host.rerollPreview();
                    })
                    .build();

                this.refsButton = Button.builder(
                    Component.literal("Refs"),
                    b -> Minecraft.getInstance().setScreen(new RefEditorScreen(host, host.buffer(), host.chain(), segIdx, shipped))
                ).bounds(0, 0, 44, 18).build();

                this.deleteButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.delete"),
                    b -> host.removeSegment(segIdx)
                ).bounds(0, 0, 22, 18).build();

                this.upButton = Button.builder(
                    Component.literal("▲"),
                    b -> host.moveSegment(displayPos, -1)
                ).bounds(0, 0, 16, 18).build();
                this.upButton.active = displayPos > 0;

                this.downButton = Button.builder(
                    Component.literal("▼"),
                    b -> host.moveSegment(displayPos, +1)
                ).bounds(0, 0, 16, 18).build();
                this.downButton.active = displayPos < totalCount - 1;
            }

            private void resetRow() {
                // Full reset: drop the entire segment_overrides[chain#segIdx]
                // entry on save so the segment falls back to shipped behaviour.
                // Also clears any pending field edits for this segment.
                host.buffer().resetSegment(host.chain().id(), segIdx);
                slider.setSliderValue(shipped.chance());
                setChanceBox(formatChance(shipped.chance()));
                setConnectionBox(shipped.connection());
                setLabelBox("");
                if (newlineBox.selected() != shipped.newline()) newlineBox.onPress();
                host.rerollPreview();
            }

            private void setLabelBox(String text) {
                suppressLabelResponder = true;
                try { labelBox.setValue(text); } finally { suppressLabelResponder = false; }
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
                if (host.deleteMode()) {
                    return List.of(upButton, downButton, labelBox, slider, chanceBox, resetButton, connectionBox, newlineBox, refsButton, deleteButton);
                }
                return List.of(upButton, downButton, labelBox, slider, chanceBox, resetButton, connectionBox, newlineBox, refsButton);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                if (host.deleteMode()) {
                    return List.of(upButton, downButton, labelBox, slider, chanceBox, resetButton, connectionBox, newlineBox, refsButton, deleteButton);
                }
                return List.of(upButton, downButton, labelBox, slider, chanceBox, resetButton, connectionBox, newlineBox, refsButton);
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int x = rowLeft + 4;
                int y = rowTop + (rowHeight - 18) / 2;
                int textY = rowTop + (rowHeight - 9) / 2;

                // ▲ / ▼ reorder buttons (stack the up/down for compactness).
                upButton.setX(x); upButton.setY(y - 5);
                upButton.setWidth(16);
                upButton.setHeight(11);
                upButton.render(gfx, mouseX, mouseY, partial);
                downButton.setX(x); downButton.setY(y + 6);
                downButton.setWidth(16);
                downButton.setHeight(11);
                downButton.render(gfx, mouseX, mouseY, partial);
                x += 20;

                // Editable label box (placeholder "Seg N" when empty).
                setW(labelBox, 80); labelBox.setX(x); labelBox.setY(y); labelBox.render(gfx, mouseX, mouseY, partial);
                x += 84;

                setW(slider, 80); slider.setX(x); slider.setY(y); slider.render(gfx, mouseX, mouseY, partial); x += 84;
                setW(chanceBox, 40); chanceBox.setX(x); chanceBox.setY(y); chanceBox.render(gfx, mouseX, mouseY, partial); x += 44;
                setW(resetButton, 18); resetButton.setX(x); resetButton.setY(y); resetButton.render(gfx, mouseX, mouseY, partial); x += 22;

                int deleteWidgetW = host.deleteMode() ? (4 + 22) : 0;
                int trailingWidgets = 18 + 4 + 44 + deleteWidgetW; // newlineBox + refsButton + (optional deleteButton) + spacing
                int connAvail = Math.max(40, rowLeft + rowWidth - x - 4 - trailingWidgets);
                setW(connectionBox, connAvail); connectionBox.setX(x); connectionBox.setY(y); connectionBox.render(gfx, mouseX, mouseY, partial); x += connAvail + 4;

                newlineBox.setX(x); newlineBox.setY(y); newlineBox.render(gfx, mouseX, mouseY, partial); x += 18 + 4;

                refsButton.setX(x); refsButton.setY(y); refsButton.render(gfx, mouseX, mouseY, partial); x += 44 + 4;

                if (host.deleteMode()) {
                    deleteButton.setX(x); deleteButton.setY(y); deleteButton.render(gfx, mouseX, mouseY, partial);
                }
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
