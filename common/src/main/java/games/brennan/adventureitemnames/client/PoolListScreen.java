package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.PoolDeleter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.List;

/**
 * Per-datapack pool table. Rows are pools contributed by this pack, with
 * editable weight (when the pool is referenced by
 * {@code title_combinations}), live %, entry count, "used in" chain
 * list, and an enable/disable checkbox.
 *
 * <p>Pending edits live in the shared {@link EditBuffer}. The
 * <strong>Save to pack</strong> button at the bottom flushes through
 * {@link UserConfigWriter} and re-runs {@link UserConfigLoader} so the
 * runtime layer picks up the new state without requiring {@code /reload}.
 */
@Environment(EnvType.CLIENT)
public final class PoolListScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Column right-edge offsets shared by header + row, so they always align. */
    static final int COL_W_PREVIEW = 28;
    static final int COL_W_DELETE  = 70;
    static final int COL_W_EDIT    = 112;
    static final int COL_W_ENABLED = 148;
    static final int COL_W_ENTRIES = 188;
    static final int COL_W_PCT     = 244;
    static final int COL_W_WEIGHT  = 312;
    private static final int HEADER_Y = 44;
    private static final int LIST_TOP = 58;

    private final Screen parent;
    private final EditBuffer buffer;
    private final PackGrouping.PackView pack;
    private PoolList list;
    private PreviewPanel preview;
    private Button saveButton;
    private ConfirmDialog activeConfirm;
    private String openFingerprint;
    private boolean deleteMode;

    public PoolListScreen(Screen parent, EditBuffer buffer, PackGrouping.PackView pack) {
        super(Component.literal(pack.packId()));
        this.parent = parent;
        this.buffer = buffer;
        this.pack = pack;
    }

    @Override
    protected void init() {
        int listBottom = height - PreviewPanel.currentHeight() - 32;
        list = new PoolList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, this);
        addRenderableWidget(list);

        int footerY = height - PreviewPanel.currentHeight() - 26;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, footerY, 80, 20).build());

        Button newPoolButton = Button.builder(
            Component.translatable("screen.adventureitemnames.pools.new_pool"),
            b -> Minecraft.getInstance().setScreen(new CreatePoolPopup(this, buffer, pack))
        ).bounds(width / 2 - 60, footerY, 120, 20).build();
        newPoolButton.active = games.brennan.adventureitemnames.internal.PoolCreator.canWriteTo(pack.packId());
        if (!newPoolButton.active) {
            newPoolButton.setTooltip(Tooltip.create(
                Component.translatable("screen.adventureitemnames.pools.new_pool.read_only")));
        }
        addRenderableWidget(newPoolButton);

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

        if (preview == null) preview = new PreviewPanel(buffer, null, this::rebuildWidgets);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
        addRenderableWidget(preview.toggleButton());

        if (openFingerprint == null) openFingerprint = BufferFingerprint.of(buffer);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (activeConfirm != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeConfirm.render(gfx, mouseX, mouseY);
            return;
        }
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font,
            Component.translatable("screen.adventureitemnames.pools.title",
                PackGrouping.friendlyPackName(pack.packId())),
            width / 2, 18, 0xFFFFFFFF);

        gfx.drawString(font, "Pool",    16,                       HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Weight",  width - COL_W_WEIGHT,     HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "%",       width - COL_W_PCT,        HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Entries", width - COL_W_ENTRIES,    HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Enabled", width - COL_W_ENABLED,    HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Edit",    width - COL_W_EDIT,       HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "🎲",      width - COL_W_PREVIEW,    HEADER_Y, 0xFFA0A0A0, false);

        if (saveButton != null) saveButton.active = buffer.isDirty();
        preview.render(gfx, mouseX, mouseY, partial);
    }

    private void save() {
        ConfigSave.commit(buffer, () -> {
            saveButton.active = false;
            preview.rerollNow();
        });
    }

    private void toggleDeleteMode() {
        deleteMode = !deleteMode;
        rebuildWidgets();
    }

    boolean deleteMode() { return deleteMode; }

    void confirmDeletePool(PackGrouping.PoolView pv) {
        String friendly = PackGrouping.friendlyPoolName(pv.packId(), pv.poolId().getPath());
        activeConfirm = new ConfirmDialog(width, height,
            Component.translatable("screen.adventureitemnames.delete.title", friendly).getString(),
            Component.translatable("screen.adventureitemnames.delete.pool.body").getString(),
            Component.translatable("screen.adventureitemnames.delete.confirm").getString(),
            new ConfirmDialog.Listener() {
                @Override public void onConfirm() {
                    activeConfirm = null;
                    boolean ok = PoolDeleter.deletePool(pv.packId(), pv.poolId().getPath());
                    if (!ok) return;
                    PackReload.reloadInPlace(
                        () -> Minecraft.getInstance().setScreen(new PoolListScreen(parent, buffer, freshPackView())));
                }
                @Override public void onCancel() { activeConfirm = null; }
            });
    }

    /** Re-snapshot the live pack so navigating back after a delete reflects the missing pool. */
    private PackGrouping.PackView freshPackView() {
        var snapshot = PackGrouping.snapshot();
        PackGrouping.PackView refreshed = snapshot.get(pack.packId());
        return refreshed != null ? refreshed : pack;
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

    EditBuffer buffer() { return buffer; }

    /** Datapacks list this screen returns to on Back — used by {@link CreatePoolPopup} when rebuilding post-reload. */
    Screen parentScreen() { return parent; }

    PackGrouping.PackView pack() { return pack; }

    void openEntryEditor(net.minecraft.resources.ResourceLocation poolId) {
        NamePool live = NameRegistry.pool(poolId).orElse(null);
        if (live == null) {
            LOGGER.warn("[AdventureItemNames] pool '{}' missing from registry — cannot open entry editor", poolId);
            return;
        }
        Minecraft.getInstance().setScreen(new PoolEntriesScreen(this, buffer, live));
    }

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    void rerollPreviewForcedPool(net.minecraft.resources.ResourceLocation poolId) {
        if (preview == null) return;
        Button oldReroll = preview.button();
        Button oldToggle = preview.toggleButton();
        PreviewPanel forced = new PreviewPanel(buffer, poolId, this::rebuildWidgets);
        forced.rebuild(width, height);
        forced.rerollNow();
        if (oldReroll != null) this.removeWidget(oldReroll);
        if (oldToggle != null) this.removeWidget(oldToggle);
        this.preview = forced;
        this.addRenderableWidget(forced.button());
        this.addRenderableWidget(forced.toggleButton());
    }

    /** Vanilla {@code ContainerObjectSelectionList} with one entry per pool. */
    static final class PoolList extends ContainerObjectSelectionList<PoolList.Entry> {

        PoolList(Minecraft mc, int width, int height, int top, PoolListScreen host) {
            super(mc, width, height, top, 24);
            for (PackGrouping.PoolView pv : host.pack.pools()) {
                addEntry(new Entry(pv, host));
            }
        }

        @Override
        public int getRowWidth() { return width - 32; }

        @Override
        protected int getScrollbarPosition() { return width - 12; }

        static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final PackGrouping.PoolView pv;
            private final PoolListScreen host;
            private final EditBox weightBox;
            private final Checkbox enabledBox;
            private final Button preview;
            private final Button editButton;
            private final Button deleteButton;
            private final int screenWidth;

            Entry(PackGrouping.PoolView pv, PoolListScreen host) {
                this.pv = pv;
                this.host = host;
                this.screenWidth = host.width;
                this.weightBox = new EditBox(Minecraft.getInstance().font, 0, 0, 56, 16,
                    Component.literal("weight"));
                if (pv.titleSlot() == null) {
                    weightBox.setEditable(false);
                    weightBox.setValue("—");
                } else {
                    float live = PackGrouping.effectiveWeightWithBuffer(host.buffer(),
                        pv.titleSlot().chainId(), pv.titleSlot().segmentIndex(),
                        new games.brennan.adventureitemnames.api.NameSegment.WeightedRef(
                            pv.poolId(), pv.titleSlot().shippedWeight()));
                    weightBox.setValue(formatWeight(live));
                    weightBox.setResponder(this::onWeightChanged);
                }

                boolean enabledNow = NamingConfig.isPoolEnabled(pv.poolId());
                Boolean pending = host.buffer().pendingPoolEnabled(pv.poolId());
                if (pending != null) enabledNow = pending;
                this.enabledBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(enabledNow)
                    .onValueChange((c, v) -> {
                        host.buffer().setPoolEnabled(pv.poolId(), v);
                    })
                    .build();

                this.preview = Button.builder(Component.literal("🎲"),
                    b -> host.rerollPreviewForcedPool(pv.poolId()))
                    .bounds(0, 0, 24, 16)
                    .build();

                this.editButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.edit_entries"),
                    b -> host.openEntryEditor(pv.poolId()))
                    .bounds(0, 0, 36, 18)
                    .build();

                this.deleteButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.delete"),
                    b -> host.confirmDeletePool(pv)
                ).bounds(0, 0, 22, 18).build();
                if (!PoolDeleter.canDelete(pv.packId())) {
                    deleteButton.active = false;
                    deleteButton.setTooltip(Tooltip.create(
                        Component.translatable("screen.adventureitemnames.delete.read_only")));
                }
            }

            private void onWeightChanged(String text) {
                if (pv.titleSlot() == null) return;
                if (text.isEmpty()) return;
                try {
                    float v = Float.parseFloat(text);
                    if (v < 0f) return;
                    host.buffer().setWeight(pv.titleSlot().chainId(),
                        pv.titleSlot().segmentIndex(), pv.poolId(), v);
                    host.rerollPreview();
                } catch (NumberFormatException ex) {
                    // Ignore transient invalid input; user is still typing.
                }
            }

            private static String formatWeight(float v) {
                if (v == 0f) return "0";
                return String.format("%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return host.deleteMode()
                    ? List.of(weightBox, enabledBox, editButton, deleteButton, preview)
                    : List.of(weightBox, enabledBox, editButton, preview);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return host.deleteMode()
                    ? List.of(weightBox, enabledBox, editButton, deleteButton, preview)
                    : List.of(weightBox, enabledBox, editButton, preview);
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int textY = rowTop + 8;
                String poolName = PackGrouping.friendlyPoolName(pv.packId(), pv.poolId().getPath());
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(poolName).withStyle(ChatFormatting.WHITE),
                    16, textY, 0xFFFFFFFF, false);

                weightBox.setX(screenWidth - COL_W_WEIGHT);
                weightBox.setY(rowTop + 3);
                weightBox.render(gfx, mouseX, mouseY, partial);

                gfx.drawString(Minecraft.getInstance().font,
                    formatPct(), screenWidth - COL_W_PCT, textY, 0xFFE0E0E0, false);

                gfx.drawString(Minecraft.getInstance().font,
                    Integer.toString(liveEntryCount()),
                    screenWidth - COL_W_ENTRIES, textY, 0xFFE0E0E0, false);

                enabledBox.setX(screenWidth - COL_W_ENABLED);
                enabledBox.setY(rowTop + 3);
                enabledBox.render(gfx, mouseX, mouseY, partial);

                editButton.setX(screenWidth - COL_W_EDIT);
                editButton.setY(rowTop + 3);
                editButton.render(gfx, mouseX, mouseY, partial);

                if (host.deleteMode()) {
                    deleteButton.setX(screenWidth - COL_W_DELETE);
                    deleteButton.setY(rowTop + 3);
                    deleteButton.render(gfx, mouseX, mouseY, partial);
                }

                preview.setX(screenWidth - COL_W_PREVIEW - 16);
                preview.setY(rowTop + 3);
                preview.render(gfx, mouseX, mouseY, partial);
            }

            private int liveEntryCount() {
                NamePool live = NameRegistry.pool(pv.poolId()).orElse(null);
                if (live == null) return pv.entryCount();
                return host.buffer().effectiveEntryCount(live);
            }

            private String formatPct() {
                if (pv.titleSlot() == null) return "—";
                float total = PackGrouping.liveTitleCombinationsTotal(host.buffer());
                if (total <= 0f) return "0%";
                float live = PackGrouping.effectiveWeightWithBuffer(host.buffer(),
                    pv.titleSlot().chainId(), pv.titleSlot().segmentIndex(),
                    new games.brennan.adventureitemnames.api.NameSegment.WeightedRef(
                        pv.poolId(), pv.titleSlot().shippedWeight()));
                Boolean en = host.buffer().pendingPoolEnabled(pv.poolId());
                if (Boolean.FALSE.equals(en)) return "0%";
                return String.format("%.2f%%", (live / total) * 100f);
            }
        }
    }
}
