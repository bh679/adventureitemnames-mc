package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.PackPaths;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v3 — top-level Chains screen. Flat list of every registered
 * {@link NameChain} sorted alphabetically by path, with each row tagged
 * by <em>every</em> pack that contributed a layer to the merged chain
 * (low-priority first). Multiple tags reflect the additive-merge
 * behaviour from the chain reload listener — {@code "replace": false}
 * layers append refs, and each row surfaces the full provenance.
 *
 * <p>Reuses v1/v2's {@link EditBuffer} for pending edits and the gated
 * {@link PreviewPanel} for live preview. {@code Save to pack} flushes
 * everything via {@link ConfigSave#commit}.
 */
@Environment(EnvType.CLIENT)
public final class ChainsListScreen extends Screen {

    private static final int LIST_TOP = 32;
    /** Row height fits two stacked text lines: chain name on top, pack tags wrapping below. */
    private static final int ENTRY_H = 30;
    private static final int LINE_H = 11;
    private static final int ROW_PAD = 4;
    private static final int CHECKBOX_SIZE = 18;
    private static final int OPEN_W = 50;

    private final Screen parent;
    private final EditBuffer buffer;
    private ChainList list;
    private PreviewPanel preview;
    private Button saveButton;

    public ChainsListScreen(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.chains.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    @Override
    protected void init() {
        List<NameChain> chains = orderedChains();
        int listBottom = height - PreviewPanel.currentHeight() - 32;
        list = new ChainList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, chains, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.currentHeight() - 26, 80, 20).build());

        // + New chain — center button, dev-mode only.
        Button newChainButton = Button.builder(
            Component.translatable("screen.adventureitemnames.chains.new_chain"),
            b -> Minecraft.getInstance().setScreen(new AddChainPopup(this, buffer))
        ).bounds(width / 2 - 60, height - PreviewPanel.currentHeight() - 26, 120, 20).build();
        if (!PackPaths.projectRootAvailable()) {
            newChainButton.active = false;
            newChainButton.setTooltip(Tooltip.create(
                Component.translatable("screen.adventureitemnames.chains.new_chain.dev_only_tooltip")));
        }
        addRenderableWidget(newChainButton);

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

    EditBuffer buffer() { return buffer; }

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    /** Every registered chain, sorted alphabetically by path then namespace. */
    private static List<NameChain> orderedChains() {
        Map<ResourceLocation, NameChain> all = NameRegistry.allChains();
        List<NameChain> out = new ArrayList<>(all.values());
        out.sort(Comparator
            .comparing((NameChain c) -> c.id().getPath())
            .thenComparing(c -> c.id().getNamespace()));
        return out;
    }

    /**
     * Human-readable chain name — drops the namespace and converts the
     * {@code snake_case} path to "Sentence case", so
     * {@code adventureitemnames:weapon_name_full} becomes
     * "Weapon name full". The namespace is conveyed by the pack tag
     * rendered next to the name.
     */
    static String formatChainName(ResourceLocation id) {
        String path = id.getPath().replace('_', ' ');
        if (path.isEmpty()) return id.toString();
        return Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    static final class ChainList extends ContainerObjectSelectionList<ChainList.RowEntry> {

        ChainList(Minecraft mc, int width, int height, int top,
                  List<NameChain> chains, ChainsListScreen host) {
            super(mc, width, height, top, ENTRY_H);
            for (NameChain c : chains) addEntry(new RowEntry(c, host));
        }

        @Override public int getRowWidth() { return width - 16; }
        @Override protected int getScrollbarPosition() { return width - 6; }

        static final class RowEntry extends ContainerObjectSelectionList.Entry<RowEntry> {

            private final NameChain chain;
            private final ChainsListScreen host;
            private final Checkbox enabledBox;
            private final Button openButton;

            RowEntry(NameChain chain, ChainsListScreen host) {
                this.chain = chain;
                this.host = host;

                boolean enabledNow = NamingConfig.isChainEnabled(chain.id());
                this.enabledBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(enabledNow)
                    .onValueChange((c, v) -> {
                        // No EditBuffer slot for chain enable yet — display only.
                        c.onPress(); // toggle back
                    })
                    .build();
                this.enabledBox.active = false;

                this.openButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.open"),
                    b -> Minecraft.getInstance().setScreen(new ChainEditorScreen(host, host.buffer(), chain))
                ).bounds(0, 0, OPEN_W, 18).build();
            }

            @Override public List<? extends NarratableEntry> narratables() { return List.of(enabledBox, openButton); }
            @Override public List<? extends GuiEventListener> children() { return List.of(enabledBox, openButton); }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                var font = Minecraft.getInstance().font;
                int rightX = rowLeft + rowWidth - ROW_PAD;
                int verticalCenter = rowTop + rowHeight / 2;

                openButton.setX(rightX - OPEN_W);
                openButton.setY(verticalCenter - 9);

                enabledBox.setX(rightX - OPEN_W - 6 - CHECKBOX_SIZE);
                enabledBox.setY(verticalCenter - CHECKBOX_SIZE / 2);

                String segCount = effectiveSegmentCount(chain) + " seg";
                int segCountW = font.width(segCount);
                int segCountX = enabledBox.getX() - 6 - segCountW;
                int segCountY = verticalCenter - 4;

                // Chain name on the top line, pack tags flowing inline after
                // it and wrapping to subsequent lines if the row runs out of
                // horizontal space.
                String chainName = formatChainName(chain.id());
                int nameX = rowLeft + ROW_PAD + 4;
                int nameY = rowTop + 4;
                int nameW = font.width(chainName);
                gfx.drawString(font, Component.literal(chainName), nameX, nameY, 0xFFE8E8E8, false);

                List<String> packLabels = packLabelsFor(chain);
                int leftEdge = nameX;
                int rightEdge = segCountX - 6;
                int firstLineStart = nameX + nameW + 8;
                drawInlineWrappedTags(gfx, font, packLabels,
                    firstLineStart, nameY, leftEdge, rightEdge, /* maxLines = */ 2);

                gfx.drawString(font, Component.literal(segCount), segCountX, segCountY, 0xFFA0A0A0, false);
                enabledBox.render(gfx, mouseX, mouseY, partial);
                openButton.render(gfx, mouseX, mouseY, partial);
            }

            /**
             * Render {@code labels} as bracketed dim tags starting at
             * {@code (firstX, firstY)} and flowing right. When a chip
             * doesn't fit before {@code rightEdge}, wrap to a new line
             * aligned to {@code leftEdge}. After {@code maxLines} total
             * lines have been used, any remaining labels collapse into a
             * {@code +N more} indicator.
             */
            private static void drawInlineWrappedTags(GuiGraphics gfx, net.minecraft.client.gui.Font font,
                                                      List<String> labels, int firstX, int firstY,
                                                      int leftEdge, int rightEdge, int maxLines) {
                if (labels.isEmpty() || rightEdge <= leftEdge) return;
                int line = 1;
                int x = firstX;
                int y = firstY;
                int spacing = 4;
                for (int i = 0; i < labels.size(); i++) {
                    String chip = "[" + labels.get(i) + "]";
                    int chipW = font.width(chip);
                    if (x + chipW > rightEdge) {
                        if (line >= maxLines) {
                            int remaining = labels.size() - i;
                            String overflow = "+" + remaining + " more";
                            int ow = font.width(overflow);
                            int overflowX = Math.min(x, rightEdge - ow);
                            gfx.drawString(font, Component.literal(overflow),
                                overflowX, y, 0xFF707070, false);
                            return;
                        }
                        line++;
                        x = leftEdge;
                        y += LINE_H;
                    }
                    gfx.drawString(font, Component.literal(chip), x, y, 0xFF808080, false);
                    x += chipW + spacing;
                }
            }

            /**
             * Every pack involved in this chain — both ones that shipped a
             * layer file <em>and</em> ones whose pools / chains are
             * referenced by the merged effective refs (so user-config
             * additions surface their source packs as tags even though
             * they didn't ship a chain layer). De-duplicated by friendly
             * name; stable insertion order: contributors first, then refs.
             */
            private static List<String> packLabelsFor(NameChain chain) {
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                for (String packId : NameRegistry.packsOfChain(chain.id())) {
                    String friendly = PackGrouping.friendlyPackName(packId);
                    seen.add(friendly);
                }
                for (int segIdx = 0; segIdx < chain.segments().size(); segIdx++) {
                    NameSegment seg = chain.segments().get(segIdx);
                    List<NameSegment.WeightedRef> effective =
                        NamingConfig.effectiveSegmentRefs(chain.id(), segIdx, seg.refs());
                    for (NameSegment.WeightedRef ref : effective) {
                        String packId = sourcePackOfRef(ref.ref());
                        if (packId == null) continue;
                        seen.add(PackGrouping.friendlyPackName(packId));
                    }
                }
                return new ArrayList<>(seen);
            }

            /**
             * Visible segment count for the row: shipped + appended − removed.
             * Mirrors the composer's effective range so the displayed count
             * matches what the user sees in the per-chain editor.
             */
            private static int effectiveSegmentCount(NameChain chain) {
                int total = NamingConfig.effectiveSegmentCount(chain.id(), chain.segments().size());
                int removed = 0;
                for (int i = 0; i < total; i++) {
                    if (NamingConfig.isSegmentRemoved(chain.id(), i)) removed++;
                }
                return total - removed;
            }

            /**
             * Resolve a ref to its source pack id: pools and chains use
             * their {@code packIdOf*} side tables; context refs return
             * {@code null} (no pack — they're virtual).
             */
            private static String sourcePackOfRef(ResourceLocation refId) {
                if (refId.getPath().startsWith("context/")) return null;
                if (NameRegistry.chain(refId).isPresent()) return NameRegistry.packIdOfChain(refId);
                if (NameRegistry.pool(refId).isPresent()) return NameRegistry.packIdOfPool(refId);
                return null;
            }
        }
    }
}
