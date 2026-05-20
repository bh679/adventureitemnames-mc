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
    private NameChain chain;
    private final int segIdx;
    private NameSegment shipped;
    private final List<NameSegment.WeightedRef> liveRefs = new ArrayList<>();

    private RefList list;
    private PreviewPanel preview;
    private Button saveButton;
    private RefPicker activeRefPicker;

    public RefEditorScreen(Screen parent, EditBuffer buffer, NameChain chain, int segIdx, NameSegment shipped) {
        super(Component.literal(ChainsListScreen.formatChainName(chain.id()) + " · Seg " + segIdx + " refs"));
        this.parent = parent;
        this.buffer = buffer;
        this.chain = chain;
        this.segIdx = segIdx;
        this.shipped = shipped;
        this.liveRefs.addAll(buffer.effectiveSegmentRefs(chain.id(), segIdx, shipped.refs()));
        sortLiveRefs();
    }

    /**
     * Refresh the cached {@link #chain} / {@link #shipped} references from
     * {@link NameRegistry} so a Save in this screen (or in a child screen
     * we returned from) is reflected in subsequent reads. Without this,
     * row entries built in {@link #init} would re-display the pre-save
     * shipped refs / weights / label captured at constructor time.
     */
    private void refreshFromRegistry() {
        var refreshed = NameRegistry.chain(chain.id());
        if (refreshed.isEmpty()) return;
        chain = refreshed.get();
        List<NameSegment> segs = chain.segments();
        if (segIdx >= 0 && segIdx < segs.size()) shipped = segs.get(segIdx);
        liveRefs.clear();
        liveRefs.addAll(buffer.effectiveSegmentRefs(chain.id(), segIdx, shipped.refs()));
        sortLiveRefs();
    }

    @Override
    protected void init() {
        refreshFromRegistry();
        int listBottom = height - PreviewPanel.currentHeight() - 32;
        list = new RefList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.currentHeight() - 26, 80, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("+ Add ref"),
            b -> openRefPicker()
        ).bounds(width / 2 - 60, height - PreviewPanel.currentHeight() - 26, 120, 20).build());

        saveButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.save"),
            b -> save()
        ).bounds(width - 88, height - PreviewPanel.currentHeight() - 26, 80, 20).build();
        saveButton.active = buffer.isDirty();
        addRenderableWidget(saveButton);

        if (preview == null) preview = new PreviewPanel(buffer, null, true, this::rebuildWidgets);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
        addRenderableWidget(preview.toggleButton());
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
        // Exclusions:
        //   1. Refs already on THIS segment (avoids duplicates in the row
        //      list; a ref can still appear on multiple segments of the
        //      chain — different segments can re-use refs intentionally).
        //   2. The chain being edited itself (self-reference would loop
        //      in the composer).
        java.util.Set<ResourceLocation> excluded = new java.util.HashSet<>();
        excluded.add(chain.id());
        for (NameSegment.WeightedRef r : liveRefs) excluded.add(r.ref());

        List<RefPicker.Entry> entries = new ArrayList<>();
        NameRegistry.allChains().keySet().stream()
            .filter(rl -> !excluded.contains(rl))
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(rl -> entries.add(new RefPicker.Entry(rl, RefPicker.Kind.CHAIN)));
        NameRegistry.allPools().keySet().stream()
            .filter(rl -> !excluded.contains(rl))
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(rl -> entries.add(new RefPicker.Entry(rl, RefPicker.Kind.POOL)));
        activeRefPicker = new RefPicker(width, height, "Pick ref to add", entries, new RefPicker.Listener() {
            @Override public void onPicked(java.util.Set<ResourceLocation> refs) {
                activeRefPicker = null;
                addRefs(refs);
            }
            @Override public void onCancelled() {
                activeRefPicker = null;
            }
        });
    }

    private void addRefs(java.util.Set<ResourceLocation> refIds) {
        if (refIds == null || refIds.isEmpty()) return;
        for (ResourceLocation refId : refIds) {
            liveRefs.add(new NameSegment.WeightedRef(refId, defaultWeightFor(refId)));
        }
        sortLiveRefs();
        persistRefList();
        if (list != null) list.rebuild();
        rerollPreview();
    }

    void removeRef(int rowIdx) {
        if (rowIdx < 0 || rowIdx >= liveRefs.size()) return;
        NameSegment.WeightedRef removed = liveRefs.remove(rowIdx);
        // Drop any pending weight override targeting that ref — the row is gone.
        buffer.clearWeight(chain.id(), segIdx, removed.ref());
        sortLiveRefs();
        persistRefList();
        if (list != null) list.rebuild();
        rerollPreview();
    }

    /**
     * Default weight for a newly-added ref: pool refs default to their
     * entry count so each entry has roughly uniform 1/N odds across the
     * whole segment. Chain and context refs default to {@code 1.0}.
     */
    private static float defaultWeightFor(ResourceLocation refId) {
        if (refId.getPath().startsWith("context/")) return 1.0f;
        var pool = NameRegistry.pool(refId);
        if (pool.isPresent()) return Math.max(1, pool.get().entries().size());
        return 1.0f;
    }

    /**
     * Sort {@link #liveRefs} by pack → weight desc → entry-count desc →
     * alphabetical name. Run after add / remove / initial load — not on
     * every weight edit (that would yank the row out from under the
     * user's cursor while typing).
     */
    private void sortLiveRefs() {
        liveRefs.sort((a, b) -> {
            int p = sortPackKey(a.ref()).compareTo(sortPackKey(b.ref()));
            if (p != 0) return p;
            int w = Float.compare(effectiveWeightFor(b), effectiveWeightFor(a));
            if (w != 0) return w;
            int e = Integer.compare(entryCountOf(b.ref()), entryCountOf(a.ref()));
            if (e != 0) return e;
            return ChainsListScreen.formatChainName(a.ref())
                .compareToIgnoreCase(ChainsListScreen.formatChainName(b.ref()));
        });
    }

    /** Sort key for the pack column: context first, then friendly pack name. Unresolved refs go last. */
    private static String sortPackKey(ResourceLocation id) {
        if (id.getPath().startsWith("context/")) return " ";
        if (NameRegistry.chain(id).isPresent()) {
            return PackGrouping.friendlyPackName(NameRegistry.packIdOfChain(id));
        }
        if (NameRegistry.pool(id).isPresent()) {
            return PackGrouping.friendlyPackName(NameRegistry.packIdOfPool(id));
        }
        return "￿"; // unresolved
    }

    private float effectiveWeightFor(NameSegment.WeightedRef ref) {
        Float pending = buffer.pendingWeight(chain.id(), segIdx, ref.ref());
        if (pending != null) return Math.max(0f, pending);
        return NamingConfig.effectiveWeight(chain.id(), segIdx, ref.ref(), ref.weight());
    }

    private static int entryCountOf(ResourceLocation id) {
        if (id.getPath().startsWith("context/")) return 0;
        return NameRegistry.pool(id).map(p -> p.entries().size()).orElse(0);
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
            /** Last-rendered hit-box for the clickable name region (screen coords). */
            private int nameClickX1, nameClickY1, nameClickX2, nameClickY2;

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
                    // Always store the weight — the previous "match shipped → clear"
                    // shortcut silently dropped edits whenever the typed value
                    // coincidentally matched the shipped weight. Use 🗑 to remove a
                    // ref or the ↺ reset on the segment row to revert everything.
                    host.buffer().setWeight(host.chain().id(), host.segIdx(), ref.ref(), parsed);
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
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0
                    && mouseX >= nameClickX1 && mouseX < nameClickX2
                    && mouseY >= nameClickY1 && mouseY < nameClickY2) {
                    openDrilldown();
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            /** True when this ref resolves to a pool or chain — context refs are not drillable. */
            private boolean isDrillable(ResourceLocation id) {
                if (id.getPath().startsWith("context/")) return false;
                return NameRegistry.pool(id).isPresent() || NameRegistry.chain(id).isPresent();
            }

            /** Open the appropriate drill-in screen for this ref (pool entries or chain editor). */
            private void openDrilldown() {
                ResourceLocation id = ref.ref();
                var pool = NameRegistry.pool(id);
                if (pool.isPresent()) {
                    Minecraft.getInstance().setScreen(
                        new PoolEntriesScreen(host, host.buffer(), pool.get()));
                    return;
                }
                var chain = NameRegistry.chain(id);
                if (chain.isPresent()) {
                    Minecraft.getInstance().setScreen(
                        new ChainEditorScreen(host, host.buffer(), chain.get()));
                }
            }

            @Override
            public void render(GuiGraphics gfx, int rowIdx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                var font = Minecraft.getInstance().font;
                int textY = rowTop + (rowHeight - 9) / 2;
                int y = rowTop + (rowHeight - 18) / 2;
                int rightX = rowLeft + rowWidth - 4;

                // 🗑 on the far right
                deleteButton.setX(rightX - 22);
                deleteButton.setY(y);

                // % share label
                String shareText = formatShare(computeShare());
                int shareW = font.width(shareText);
                int shareX = rightX - 22 - 6 - shareW;

                // weight box
                int weightX = shareX - 6 - 50;
                weightBox.setX(weightX);
                weightBox.setY(y);

                // Entry / segment count (column between tags and weight box).
                String countText = countLabelFor(ref.ref());
                int countW = countText.isEmpty() ? 0 : font.width(countText);
                int countX = weightX - 8 - countW;

                // Name (left, bright, clickable for pools + chains) + pack tag(s) (dim).
                int nameX = rowLeft + 4;
                String name = formatRefName(ref.ref());
                int nameW = font.width(name);
                boolean clickable = isDrillable(ref.ref());
                boolean nameHover = clickable
                    && mouseX >= nameX && mouseX < nameX + nameW
                    && mouseY >= textY - 2 && mouseY < textY + 11;
                int nameColour = !clickable ? 0xFFE8E8E8
                    : nameHover ? 0xFFFFFFFF : 0xFFCCD8FF;
                gfx.drawString(font, Component.literal(name), nameX, textY, nameColour, false);
                if (clickable) {
                    int underlineY = textY + 9;
                    gfx.fill(nameX, underlineY, nameX + nameW, underlineY + 1, nameColour);
                }
                nameClickX1 = clickable ? nameX : 0;
                nameClickY1 = clickable ? textY - 2 : 0;
                nameClickX2 = clickable ? nameX + nameW : 0;
                nameClickY2 = clickable ? textY + 11 : 0;

                int tagsX = nameX + nameW + 8;
                int tagsMaxX = countX - 6;
                int cursor = tagsX;
                for (String tag : tagsFor(ref.ref())) {
                    String chip = "[" + tag + "]";
                    int avail = Math.max(0, tagsMaxX - cursor);
                    if (avail <= 0) break;
                    String trimmed = font.plainSubstrByWidth(chip, avail);
                    gfx.drawString(font, Component.literal(trimmed), cursor, textY, 0xFF808080, false);
                    cursor += font.width(trimmed) + 4;
                }

                if (!countText.isEmpty()) {
                    gfx.drawString(font, Component.literal(countText), countX, textY, 0xFF909090, false);
                }
                gfx.drawString(font, Component.literal(shareText), shareX, textY, 0xFFB0B0B0, false);
                weightBox.render(gfx, mouseX, mouseY, partial);
                deleteButton.render(gfx, mouseX, mouseY, partial);
            }

            /**
             * Right-aligned count column for a ref: pools show their entry
             * count, chains show their segment count, context refs and
             * unresolved refs render nothing (no fixed-size data behind them).
             */
            private static String countLabelFor(ResourceLocation id) {
                if (id.getPath().startsWith("context/")) return "";
                var pool = NameRegistry.pool(id);
                if (pool.isPresent()) return pool.get().entries().size() + " entries";
                var chain = NameRegistry.chain(id);
                if (chain.isPresent()) return chain.get().segments().size() + " seg";
                return "";
            }

            /**
             * Human-readable name for a ref. Drops the namespace; replaces
             * underscores with spaces; sentence-cases the leading word.
             * Context refs (path starts with {@code context/}) drop the
             * {@code context/} prefix too so {@code item_material} reads
             * as "Item material".
             */
            private static String formatRefName(ResourceLocation id) {
                String path = id.getPath();
                if (path.startsWith("context/")) path = path.substring("context/".length());
                String label = path.replace('_', ' ').replace("/", " / ");
                if (label.isEmpty()) return id.toString();
                return Character.toUpperCase(label.charAt(0)) + label.substring(1);
            }

            /**
             * Tags to render next to a ref name: {@code [context]} for
             * virtual refs, every contributing-pack label for chains, the
             * source-pack label for pools. Empty list = no tags rendered.
             */
            private static List<String> tagsFor(ResourceLocation id) {
                if (id.getPath().startsWith("context/")) return List.of("context");
                if (NameRegistry.chain(id).isPresent()) {
                    List<String> raw = NameRegistry.packsOfChain(id);
                    if (raw.isEmpty()) return List.of();
                    List<String> out = new java.util.ArrayList<>(raw.size());
                    for (String p : raw) {
                        String friendly = PackGrouping.friendlyPackName(p);
                        if (out.isEmpty() || !out.get(out.size() - 1).equals(friendly)) out.add(friendly);
                    }
                    return out;
                }
                if (NameRegistry.pool(id).isPresent()) {
                    return List.of(PackGrouping.friendlyPackName(NameRegistry.packIdOfPool(id)));
                }
                return List.of("unresolved");
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
