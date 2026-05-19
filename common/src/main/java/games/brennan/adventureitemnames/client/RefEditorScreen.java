package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v3 — per-segment ref editor (one level below {@link ChainEditorScreen}).
 * Lists every {@code refs[]} entry of one {@link NameSegment} with three
 * controls per row: weight text-box, computed %-share label, and
 * {@code 🗑️} remove. A footer {@code + Add ref} button opens
 * {@link RefPicker} to pick from chain + pool ids.
 *
 * <p>Weight edits flow through the existing {@code pendingWeights} map
 * (keyed {@code chainId#segIdx#refId}). Add / remove edits flow through
 * the new {@code pendingSegmentEdits} via {@code setSegmentRefs} — they
 * stage a full replacement of the shipped {@code refs} list on save.
 * If the user reverts the list back to the shipped baseline, the refs
 * override is cleared.
 */
@Environment(EnvType.CLIENT)
public final class RefEditorScreen extends Screen {

    private static final int LIST_TOP = 32;
    private static final int ENTRY_H = 24;

    private final Screen parent;
    private final EditBuffer buffer;
    private final NameChain chain;
    private final int segIdx;
    private final NameSegment shipped;
    private final List<NameSegment.WeightedRef> liveRefs = new ArrayList<>();

    private RefList list;
    private PreviewPanel preview;
    private Button saveButton;
    private RefPicker activeRefPicker;

    public RefEditorScreen(Screen parent, EditBuffer buffer, NameChain chain, int segIdx, NameSegment shipped) {
        super(Component.literal(chain.id() + " #" + segIdx + " — refs"));
        this.parent = parent;
        this.buffer = buffer;
        this.chain = chain;
        this.segIdx = segIdx;
        this.shipped = shipped;
        this.liveRefs.addAll(buffer.effectiveSegmentRefs(chain.id(), segIdx, shipped.refs()));
    }

    @Override
    protected void init() {
        int listBottom = height - PreviewPanel.HEIGHT - 32;
        list = new RefList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.HEIGHT - 26, 80, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("+ Add ref"),
            b -> openRefPicker()
        ).bounds(width / 2 - 60, height - PreviewPanel.HEIGHT - 26, 120, 20).build());

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
        if (activeRefPicker != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeRefPicker.render(gfx, mouseX, mouseY);
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

    private void openRefPicker() {
        List<RefPicker.Entry> entries = new ArrayList<>();
        NameRegistry.allChains().keySet().stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(rl -> entries.add(new RefPicker.Entry(rl, RefPicker.Kind.CHAIN)));
        NameRegistry.allPools().keySet().stream()
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(rl -> entries.add(new RefPicker.Entry(rl, RefPicker.Kind.POOL)));
        activeRefPicker = new RefPicker(width, height, "Pick ref to add", entries, new RefPicker.Listener() {
            @Override public void onPicked(ResourceLocation ref) {
                activeRefPicker = null;
                addRef(ref);
            }
            @Override public void onCancelled() {
                activeRefPicker = null;
            }
        });
    }

    private void addRef(ResourceLocation refId) {
        liveRefs.add(new NameSegment.WeightedRef(refId, 1.0f));
        persistRefList();
        if (list != null) list.rebuild();
        rerollPreview();
    }

    void removeRef(int rowIdx) {
        if (rowIdx < 0 || rowIdx >= liveRefs.size()) return;
        NameSegment.WeightedRef removed = liveRefs.remove(rowIdx);
        // Drop any pending weight override targeting that ref — the row is gone.
        buffer.clearWeight(chain.id(), segIdx, removed.ref());
        persistRefList();
        if (list != null) list.rebuild();
        rerollPreview();
    }

    /**
     * Snapshot the current {@link #liveRefs} into the buffer. If it
     * matches the shipped list exactly (ids + weights), the refs override
     * is cleared so the saved config stays minimal.
     */
    private void persistRefList() {
        if (matchesShipped(liveRefs, shipped.refs())) {
            buffer.setSegmentRefs(chain.id(), segIdx, null);
        } else {
            buffer.setSegmentRefs(chain.id(), segIdx, new ArrayList<>(liveRefs));
        }
    }

    private static boolean matchesShipped(List<NameSegment.WeightedRef> a, List<NameSegment.WeightedRef> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).ref().equals(b.get(i).ref())) return false;
            if (Math.abs(a.get(i).weight() - b.get(i).weight()) > 1e-5f) return false;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeRefPicker != null) { activeRefPicker.mouseClicked(mouseX, mouseY, button); return true; }
        if (preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeRefPicker != null && activeRefPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeRefPicker != null && activeRefPicker.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (activeRefPicker != null && activeRefPicker.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    EditBuffer buffer() { return buffer; }

    NameChain chain() { return chain; }

    int segIdx() { return segIdx; }

    List<NameSegment.WeightedRef> liveRefs() { return liveRefs; }

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    static final class RefList extends ContainerObjectSelectionList<RefList.RefRow> {

        private final RefEditorScreen host;

        RefList(Minecraft mc, int width, int height, int top, RefEditorScreen host) {
            super(mc, width, height, top, ENTRY_H);
            this.host = host;
            rebuild();
        }

        void rebuild() {
            children().clear();
            List<NameSegment.WeightedRef> refs = host.liveRefs();
            for (int i = 0; i < refs.size(); i++) addEntry(new RefRow(i, refs.get(i), host));
        }

        @Override public int getRowWidth() { return width - 16; }
        @Override protected int getScrollbarPosition() { return width - 6; }

        static final class RefRow extends ContainerObjectSelectionList.Entry<RefRow> {

            private final int idx;
            private final NameSegment.WeightedRef ref;
            private final RefEditorScreen host;
            private final EditBox weightBox;
            private final Button deleteButton;
            private boolean suppressWeightResponder;

            RefRow(int idx, NameSegment.WeightedRef ref, RefEditorScreen host) {
                this.idx = idx;
                this.ref = ref;
                this.host = host;

                float effective = NamingConfig.effectiveWeight(host.chain().id(), host.segIdx(), ref.ref(), ref.weight());
                Float pending = host.buffer().pendingWeight(host.chain().id(), host.segIdx(), ref.ref());
                if (pending != null) effective = Math.max(0f, pending);

                this.weightBox = new EditBox(Minecraft.getInstance().font, 0, 0, 50, 18, Component.literal("w"));
                this.weightBox.setMaxLength(8);
                this.weightBox.setValue(formatWeight(effective));
                this.weightBox.setResponder(text -> {
                    if (suppressWeightResponder) return;
                    Float parsed = tryParseWeight(text);
                    if (parsed == null) return;
                    if (parsed == ref.weight()) {
                        host.buffer().clearWeight(host.chain().id(), host.segIdx(), ref.ref());
                    } else {
                        host.buffer().setWeight(host.chain().id(), host.segIdx(), ref.ref(), parsed);
                    }
                    host.rerollPreview();
                });

                this.deleteButton = Button.builder(
                    Component.literal("🗑"),
                    b -> host.removeRef(idx)
                ).bounds(0, 0, 22, 18).build();
            }

            @Override public List<? extends NarratableEntry> narratables() { return List.of(weightBox, deleteButton); }
            @Override public List<? extends GuiEventListener> children() { return List.of(weightBox, deleteButton); }

            @Override
            public void render(GuiGraphics gfx, int rowIdx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int x = rowLeft + 4;
                int textY = rowTop + (rowHeight - 9) / 2;
                int y = rowTop + (rowHeight - 18) / 2;

                String idText = (NameRegistry.chain(ref.ref()).isPresent() ? "[c] " : "[p] ") + ref.ref();
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(idText), x, textY, 0xFFE0E0E0, false);

                int rightX = rowLeft + rowWidth - 4;

                // 🗑️ on the far right
                deleteButton.setX(rightX - 22);
                deleteButton.setY(y);
                deleteButton.render(gfx, mouseX, mouseY, partial);

                // % share label
                String shareText = formatShare(computeShare());
                int shareW = Minecraft.getInstance().font.width(shareText);
                int shareX = rightX - 22 - 6 - shareW;
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(shareText), shareX, textY, 0xFFB0B0B0, false);

                // weight box
                weightBox.setX(shareX - 6 - 50);
                weightBox.setY(y);
                weightBox.render(gfx, mouseX, mouseY, partial);
            }

            private float computeShare() {
                float total = 0f;
                for (NameSegment.WeightedRef r : host.liveRefs()) {
                    float w = NamingConfig.effectiveWeight(host.chain().id(), host.segIdx(), r.ref(), r.weight());
                    Float p = host.buffer().pendingWeight(host.chain().id(), host.segIdx(), r.ref());
                    if (p != null) w = Math.max(0f, p);
                    total += w;
                }
                if (total <= 0f) return 0f;
                float mine = NamingConfig.effectiveWeight(host.chain().id(), host.segIdx(), ref.ref(), ref.weight());
                Float p = host.buffer().pendingWeight(host.chain().id(), host.segIdx(), ref.ref());
                if (p != null) mine = Math.max(0f, p);
                return mine / total;
            }

            private static String formatWeight(float v) {
                if (v == (int) v) return Integer.toString((int) v);
                return String.format("%.2f", v);
            }

            private static String formatShare(float frac) {
                return String.format("%.0f%%", frac * 100f);
            }

            private static Float tryParseWeight(String text) {
                if (text == null || text.isEmpty()) return null;
                try {
                    float v = Float.parseFloat(text.trim());
                    if (Float.isNaN(v) || v < 0f) return null;
                    return v;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
    }
}
