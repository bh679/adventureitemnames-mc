package games.brennan.adventureitemnames.client;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamePool;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.UserConfigLoader;
import games.brennan.adventureitemnames.internal.UserConfigWriter;
import games.brennan.adventureitemnames.internal.UserEdits;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;

/**
 * Per-pool entries table. Rows are the effective entries of one
 * {@link NamePool} after shipped → USER → API → buffer overlay,
 * rendered one per row with an inline text {@link EditBox}, a
 * read-only {@code item_types} badge, a Δ indicator (added /
 * edited), and a delete button. The "Add entry" controls along the
 * top stage new entries through the shared {@link EditBuffer}.
 *
 * <p>Pending edits flow through {@link EditBuffer}; the
 * <strong>Save to pack</strong> button flushes through
 * {@link UserConfigWriter} and re-runs {@link UserConfigLoader} so the
 * runtime layer picks up the new state without requiring
 * {@code /reload}. Mirrors the persistence shape of
 * {@link PoolListScreen#save()}.
 *
 * <p>The delete button is disabled on the only-remaining-row so the UI
 * can never fully blank a pool. The composer-level safety net in
 * {@code NameComposer.pickPoolEntry} covers the hand-edited-config
 * case where this UI guard doesn't apply.
 */
@Environment(EnvType.CLIENT)
public final class PoolEntriesScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Column right-edge offsets — shared by header + row so they stay aligned. */
    static final int COL_W_DELETE = 32;
    static final int COL_W_DELTA = 64;
    static final int COL_W_ITEMTYPES = 168;
    private static final int HEADER_Y = 66;
    private static final int LIST_TOP = 80;
    private static final int ADD_ROW_Y = 38;

    private final Screen parent;
    private final EditBuffer buffer;
    private final NamePool pool;
    private EntriesList list;
    private EditBox addBox;
    private Button addButton;
    private PreviewPanel preview;
    private Button saveButton;

    public PoolEntriesScreen(Screen parent, EditBuffer buffer, NamePool pool) {
        super(Component.translatable("screen.adventureitemnames.entries.title", pool.id().toString()));
        this.parent = parent;
        this.buffer = buffer;
        this.pool = pool;
    }

    @Override
    protected void init() {
        int listBottom = height - PreviewPanel.HEIGHT - 32;

        addBox = new EditBox(font, 16 + 80, ADD_ROW_Y, width - 16 - 80 - 80 - 8, 18,
            Component.translatable("screen.adventureitemnames.entries.add_placeholder"));
        addBox.setMaxLength(256);
        addBox.setHint(Component.translatable("screen.adventureitemnames.entries.add_placeholder")
            .copy().withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(addBox);

        addButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.add"),
            b -> onAddClicked()
        ).bounds(width - 16 - 72, ADD_ROW_Y, 72, 18).build();
        addRenderableWidget(addButton);

        list = new EntriesList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, this);
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

        preview = new PreviewPanel(buffer, pool.id());
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);

        gfx.drawString(font, "Add entry:", 16, ADD_ROW_Y + 5, 0xFFA0A0A0, false);

        gfx.drawString(font, "Text",       16,                          HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Item types", width - COL_W_ITEMTYPES,     HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Δ",          width - COL_W_DELTA,         HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Action",     width - COL_W_DELETE - 4,    HEADER_Y, 0xFFA0A0A0, false);

        if (saveButton != null) saveButton.active = buffer.isDirty();
        preview.render(gfx, mouseX, mouseY, partial);
    }

    private void onAddClicked() {
        String text = addBox.getValue().trim();
        if (text.isEmpty()) return;
        buffer.addEntry(pool.id(), NamePool.PoolEntry.universal(text));
        addBox.setValue("");
        rebuildList();
        preview.rerollNow();
    }

    private void rebuildList() {
        int listBottom = height - PreviewPanel.HEIGHT - 32;
        removeWidget(list);
        list = new EntriesList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, this);
        addRenderableWidget(list);
    }

    private void save() {
        UserEdits edits = new UserEdits(
            buffer.snapshotDisabledPools(),
            buffer.snapshotEnabledPools(),
            buffer.snapshotWeights(),
            buffer.snapshotEntryOverrides());
        boolean ok = UserConfigWriter.save(edits);
        if (ok) {
            UserConfigLoader.reload();
            buffer.clear();
            saveButton.active = false;
            rebuildList();
            preview.rerollNow();
            LOGGER.info("[AdventureItemNames] user config saved (entries flushed for pool {})", pool.id());
        } else {
            LOGGER.warn("[AdventureItemNames] save failed — pending edits retained");
        }
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
    NamePool pool() { return pool; }

    void onRowChanged() {
        if (saveButton != null) saveButton.active = buffer.isDirty();
        preview.rerollNow();
    }

    void onDeleteRow(String text, boolean wasShipped) {
        if (wasShipped) {
            buffer.removeShippedEntry(pool.id(), text);
        } else {
            buffer.unstageAdd(pool.id(), text);
        }
        rebuildList();
        onRowChanged();
    }

    /** Vanilla {@code ContainerObjectSelectionList} with one entry per effective row. */
    static final class EntriesList extends ContainerObjectSelectionList<EntriesList.Entry> {

        EntriesList(Minecraft mc, int width, int height, int top, PoolEntriesScreen host) {
            super(mc, width, height, top, 24);
            // Re-fetch the shipped pool from the registry so layer-aware effective view stays correct
            // even after the user-config reload swaps the runtime state.
            NamePool live = NameRegistry.pool(host.pool().id()).orElse(host.pool());
            List<NamePool.PoolEntry> effective = host.buffer().effectivePoolEntries(live);
            for (NamePool.PoolEntry e : effective) {
                boolean wasShipped = isShipped(live, e.text(), host.buffer());
                addEntry(new Entry(e, wasShipped, host, effective.size()));
            }
        }

        private static boolean isShipped(NamePool live, String text, EditBuffer buffer) {
            if (buffer.isPendingAdd(live.id(), text)) return false;
            for (NamePool.PoolEntry e : live.entries()) {
                if (e.text().equals(text)) return true;
            }
            return false;
        }

        @Override
        public int getRowWidth() { return width - 32; }

        @Override
        protected int getScrollbarPosition() { return width - 12; }

        static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final NamePool.PoolEntry entry;
            private final boolean wasShipped;
            private final PoolEntriesScreen host;
            private final EditBox textBox;
            private final Button deleteButton;
            private final int screenWidth;
            private final int totalEffectiveCount;
            /** Last value sent through {@link EditBuffer#editEntryText} — tracks "original" across edits. */
            private String currentOriginal;

            Entry(NamePool.PoolEntry entry, boolean wasShipped, PoolEntriesScreen host, int totalEffectiveCount) {
                this.entry = entry;
                this.wasShipped = wasShipped;
                this.host = host;
                this.screenWidth = host.width;
                this.totalEffectiveCount = totalEffectiveCount;
                this.currentOriginal = entry.text();

                int textBoxWidth = screenWidth - COL_W_ITEMTYPES - 16 - 8;
                this.textBox = new EditBox(Minecraft.getInstance().font, 0, 0, textBoxWidth, 16,
                    Component.literal("entry"));
                this.textBox.setMaxLength(256);
                this.textBox.setValue(entry.text());
                this.textBox.setResponder(this::onTextChanged);

                this.deleteButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.delete"),
                    b -> host.onDeleteRow(currentOriginal, wasShipped)
                ).bounds(0, 0, 22, 18).build();
                if (totalEffectiveCount <= 1) {
                    deleteButton.active = false;
                    deleteButton.setTooltip(Tooltip.create(
                        Component.translatable("screen.adventureitemnames.entries.last_entry_tooltip")));
                }
            }

            private void onTextChanged(String text) {
                String trimmed = text;
                if (trimmed.isEmpty()) return;
                if (trimmed.equals(currentOriginal)) return;
                // Always consult the buffer at edit time: a shipped row whose
                // text was just edited is now sitting in pendingAddedEntries,
                // so a SECOND edit should mutate that pending-add in place
                // rather than stage a phantom remove for a never-shipped text.
                boolean currentlyABufferAdd = host.buffer().isPendingAdd(host.pool().id(), currentOriginal);
                host.buffer().editEntryText(host.pool().id(), currentOriginal, trimmed, !currentlyABufferAdd);
                currentOriginal = trimmed;
                host.onRowChanged();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(textBox, deleteButton);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(textBox, deleteButton);
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                textBox.setX(16);
                textBox.setY(rowTop + 3);
                textBox.render(gfx, mouseX, mouseY, partial);

                String typesBadge = entry.itemTypes().isEmpty()
                    ? "(universal)"
                    : "(" + entry.itemTypes().size() + " tag" + (entry.itemTypes().size() == 1 ? ")" : "s)");
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(typesBadge).withStyle(ChatFormatting.GRAY),
                    screenWidth - COL_W_ITEMTYPES, rowTop + 8, 0xFFA0A0A0, false);

                String delta = deltaIndicator();
                int deltaColor = wasShipped && !currentOriginal.equals(entry.text())
                    ? 0xFFFFD060
                    : (wasShipped ? 0xFFA0A0A0 : 0xFF80D080);
                gfx.drawString(Minecraft.getInstance().font, delta,
                    screenWidth - COL_W_DELTA, rowTop + 8, deltaColor, false);

                deleteButton.setX(screenWidth - COL_W_DELETE);
                deleteButton.setY(rowTop + 3);
                deleteButton.render(gfx, mouseX, mouseY, partial);
            }

            private String deltaIndicator() {
                if (!wasShipped) return "+";
                // Shipped row: yellow * if the text has been edited away from the shipped value.
                if (!currentOriginal.equals(entry.text())) return "*";
                return "";
            }
        }
    }
}
