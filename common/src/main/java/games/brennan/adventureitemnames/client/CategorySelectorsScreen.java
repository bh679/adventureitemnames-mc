package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NameSelector;
import games.brennan.adventureitemnames.api.NameTier;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-tier item-selector editor — opened from one of the
 * {@code PLAIN} / {@code ENCHANTED} rows on {@link SpawnChancesScreen}.
 *
 * <p>Replaces the old shared {@code SelectorsScreen} which displayed
 * BOTH chain dropdowns in one big screen. This screen shows ONLY the
 * dropdown for the {@link NameTier} corresponding to the {@link ChanceKind}
 * the user clicked through.
 *
 * <p>The list also exposes a {@code + Add custom selector} row at the
 * bottom that routes to {@link AddSelectorScreen}, and a {@code ✕} delete
 * button on user-added rows that drops them from the {@code custom_selectors}
 * map on save.
 *
 * <p>Layout per row: icon · chain dropdown · enable checkbox · (✕ delete
 * on user rows).
 */
@Environment(EnvType.CLIENT)
public final class CategorySelectorsScreen extends Screen {

    /** Fixed display order for the supported selector paths. */
    private static final List<String> SELECTOR_PATH_ORDER = List.of(
        "sword", "axe", "pickaxe", "shovel", "hoe", "bow",
        "helmet", "chestplate", "leggings", "boots", "shield");

    private static final int ICON_SIZE       = 16;
    private static final int CHECKBOX_SIZE   = 18;
    private static final int DELETE_W        = 16;
    private static final int CELL_PAD        = 4;
    private static final int CELL_GAP        = 4;
    private static final int GAP_BETWEEN     = 6;
    private static final int LIST_TOP        = 32;
    private static final int ENTRY_H         = 22;
    private static final int DROPDOWN_H      = 18;

    private final Screen parent;
    private final EditBuffer buffer;
    private final ChanceKind category;
    private final NameTier tier;
    private SelectorList list;
    private PreviewPanel preview;
    private Button saveButton;
    private List<Optional<ResourceLocation>> chainCycle = List.of();
    private ChainPicker activePicker;

    public CategorySelectorsScreen(Screen parent, EditBuffer buffer, ChanceKind category) {
        super(titleFor(category));
        if (category != ChanceKind.PLAIN && category != ChanceKind.ENCHANTED) {
            throw new IllegalArgumentException("CategorySelectorsScreen only handles PLAIN/ENCHANTED, got " + category);
        }
        this.parent = parent;
        this.buffer = buffer;
        this.category = category;
        this.tier = category == ChanceKind.PLAIN ? NameTier.PLAIN : NameTier.ENCHANTED;
    }

    private static Component titleFor(ChanceKind category) {
        if (category == ChanceKind.PLAIN) {
            return Component.translatable("screen.adventureitemnames.selectors.plain.title");
        }
        if (category == ChanceKind.ENCHANTED) {
            return Component.translatable("screen.adventureitemnames.selectors.enchanted.title");
        }
        return Component.literal("Selectors");
    }

    @Override
    protected void init() {
        chainCycle = buildChainCycle();

        List<NameSelector> selectors = orderedSelectors();
        int listBottom = height - PreviewPanel.currentHeight() - 32;
        list = new SelectorList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, selectors, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.currentHeight() - 26, 80, 20).build());

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
        if (activePicker != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activePicker.render(gfx, mouseX, mouseY);
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
            rebuildWidgets();
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activePicker != null) {
            activePicker.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activePicker != null) {
            if (activePicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activePicker != null && activePicker.keyPressed(keyCode)) return true;
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Open the chain picker for this screen's tier on one selector. */
    void openChainPicker(ResourceLocation selectorId,
                         Optional<ResourceLocation> current,
                         java.util.function.Consumer<Optional<ResourceLocation>> onPick) {
        String tierTitle = tier == NameTier.PLAIN ? "Plain chain" : "Enchanted chain";
        activePicker = new ChainPicker(width, height, tierTitle, chainCycle, current,
            new ChainPicker.Listener() {
                @Override public void onPicked(Optional<ResourceLocation> chain) {
                    activePicker = null;
                    onPick.accept(chain);
                }
                @Override public void onCancelled() {
                    activePicker = null;
                }
            });
    }

    /** Open the "Add custom selector" form, scoped to this screen's tier. */
    void openAddSelectorScreen() {
        Minecraft.getInstance().setScreen(new AddSelectorScreen(this, buffer, tier, this::rebuildWidgets));
    }

    /** Drop a staged-or-saved user-defined selector by id. */
    void deleteCustomSelector(ResourceLocation id) {
        buffer.removeCustomSelector(id);
        rebuildWidgets();
        if (preview != null) preview.rerollNow();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    EditBuffer buffer() { return buffer; }
    NameTier tier() { return tier; }
    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    /**
     * Every visible selector — shipped selectors in
     * {@link #SELECTOR_PATH_ORDER}, then alphabetically by id, then user-added
     * selectors from the buffer's effective view.
     */
    private List<NameSelector> orderedSelectors() {
        Map<ResourceLocation, NameSelector> remainingShipped = new LinkedHashMap<>(NameRegistry.shippedSelectors());
        List<NameSelector> ordered = new ArrayList<>();
        for (String path : SELECTOR_PATH_ORDER) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("adventureitemnames", path);
            NameSelector sel = remainingShipped.remove(id);
            if (sel != null) ordered.add(sel);
        }
        remainingShipped.values().stream()
            .sorted(Comparator.comparing(s -> s.id().toString()))
            .forEach(ordered::add);
        // Custom selectors from saved-user-layer + pending buffer.
        buffer.effectiveCustomSelectors().values().stream()
            .sorted(Comparator.comparing(s -> s.id().toString()))
            .forEach(ordered::add);
        return ordered;
    }

    /** Build the dropdown cycle list: (none) first, then all chains sorted by id. */
    private static List<Optional<ResourceLocation>> buildChainCycle() {
        List<ResourceLocation> sorted = new ArrayList<>(NameRegistry.allChains().keySet());
        sorted.sort(Comparator.comparing(ResourceLocation::toString));
        List<Optional<ResourceLocation>> out = new ArrayList<>(sorted.size() + 1);
        out.add(Optional.empty());
        for (ResourceLocation rl : sorted) out.add(Optional.of(rl));
        return Collections.unmodifiableList(out);
    }

    /**
     * Map a selector path to a representative vanilla {@link ItemStack}.
     * Tool / armor selectors use the iron variant (so the icon is colour-rich);
     * shield maps to itself. Unknown paths fall through to {@link Items#AIR}.
     */
    static ItemStack iconForSelector(NameSelector sel) {
        String path = sel.id().getPath();
        String itemPath = switch (path) {
            case "sword", "axe", "pickaxe", "shovel", "hoe",
                 "helmet", "chestplate", "leggings", "boots" -> "iron_" + path;
            case "shield" -> "shield";
            default -> path;
        };
        Item item = BuiltInRegistries.ITEM.getOptional(
            ResourceLocation.fromNamespaceAndPath("minecraft", itemPath)).orElse(Items.AIR);
        return new ItemStack(item);
    }

    static final class SelectorList extends ContainerObjectSelectionList<SelectorList.Entry> {

        SelectorList(Minecraft mc, int width, int height, int top,
                     List<NameSelector> selectors, CategorySelectorsScreen host) {
            super(mc, width, height, top, ENTRY_H);
            for (int i = 0; i < selectors.size(); i += 2) {
                NameSelector left = selectors.get(i);
                NameSelector right = i + 1 < selectors.size() ? selectors.get(i + 1) : null;
                addEntry(new PairEntry(left, right, host));
            }
            addEntry(new AddEntry(host));
        }

        @Override
        public int getRowWidth() { return width - 16; }

        @Override
        protected int getScrollbarPosition() { return width - 6; }

        abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {}

        /** One list row = two selector cells side-by-side. */
        static final class PairEntry extends Entry {

            private final Cell left;
            private final Cell right;

            PairEntry(NameSelector leftSel, NameSelector rightSel, CategorySelectorsScreen host) {
                this.left = new Cell(leftSel, host);
                this.right = rightSel != null ? new Cell(rightSel, host) : null;
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                List<NarratableEntry> out = new ArrayList<>(left.widgets());
                if (right != null) out.addAll(right.widgets());
                return out;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                List<GuiEventListener> out = new ArrayList<>(left.widgets());
                if (right != null) out.addAll(right.widgets());
                return out;
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int halfWidth = (rowWidth - GAP_BETWEEN) / 2;
                left.render(gfx, rowLeft, rowTop, halfWidth, rowHeight, mouseX, mouseY, partial);
                if (right != null) {
                    right.render(gfx, rowLeft + halfWidth + GAP_BETWEEN, rowTop,
                        halfWidth, rowHeight, mouseX, mouseY, partial);
                }
            }
        }

        /**
         * Bottom-of-list {@code + Add custom selector} entry. Click routes
         * to {@link AddSelectorScreen}.
         */
        static final class AddEntry extends Entry {

            private final Button addButton;

            AddEntry(CategorySelectorsScreen host) {
                this.addButton = Button.builder(
                    Component.literal("+ Add custom selector"),
                    b -> host.openAddSelectorScreen()
                ).bounds(0, 0, 200, DROPDOWN_H).build();
            }

            @Override
            public List<? extends NarratableEntry> narratables() { return List.of(addButton); }

            @Override
            public List<? extends GuiEventListener> children() { return List.of(addButton); }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int dividerY = rowTop + 2;
                gfx.fill(rowLeft + 8, dividerY, rowLeft + rowWidth - 8, dividerY + 1, 0xFF505050);

                int btnW = Math.min(rowWidth - 32, 220);
                int btnX = rowLeft + (rowWidth - btnW) / 2;
                int btnY = rowTop + (rowHeight - DROPDOWN_H) / 2 + 1;
                addButton.setX(btnX);
                addButton.setY(btnY);
                addButton.setWidth(btnW);
                addButton.render(gfx, mouseX, mouseY, partial);
            }
        }

        /** One selector in the 2-column grid: icon · chain dropdown · enabled · (delete if user-defined). */
        static final class Cell {

            private final NameSelector sel;
            private final boolean isUserDefined;
            private final CategorySelectorsScreen host;
            private final Button chainButton;
            private final Checkbox enabledBox;
            private final Button deleteButton;

            Cell(NameSelector sel, CategorySelectorsScreen host) {
                this.sel = sel;
                this.host = host;
                this.isUserDefined = !NameRegistry.shippedSelectors().containsKey(sel.id());

                this.chainButton = makeCycleButton();

                boolean enabledNow = NamingConfig.isSelectorEnabled(sel.id());
                Boolean pending = host.buffer().pendingSelectorEnabled(sel.id());
                if (pending != null) enabledNow = pending;
                this.enabledBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(enabledNow)
                    .onValueChange((c, v) -> {
                        host.buffer().setSelectorEnabled(sel.id(), v);
                        host.rerollPreview();
                    })
                    .build();

                this.deleteButton = isUserDefined
                    ? Button.builder(
                        Component.literal("✕"),
                        b -> host.deleteCustomSelector(sel.id())
                    ).bounds(0, 0, DELETE_W, DROPDOWN_H).build()
                    : null;
            }

            List<? extends net.minecraft.client.gui.components.AbstractWidget> widgets() {
                if (deleteButton != null) return List.of(chainButton, enabledBox, deleteButton);
                return List.of(chainButton, enabledBox);
            }

            private Button makeCycleButton() {
                String tierKey = host.tier().key();
                Optional<ResourceLocation> initial = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                Component tooltipLabel = host.tier() == NameTier.PLAIN
                    ? Component.literal("Plain chain")
                    : Component.literal("Enchanted chain");
                Button btn = Button.builder(
                    Component.literal(ChainLabels.formatChainLabel(initial)),
                    b -> openPicker()
                ).bounds(0, 0, 60, DROPDOWN_H).build();
                btn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltipLabel));
                return btn;
            }

            private void openPicker() {
                String tierKey = host.tier().key();
                Optional<ResourceLocation> current = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                host.openChainPicker(sel.id(), current, picked -> {
                    host.buffer().setSelectorTier(sel.id(), tierKey, picked);
                    chainButton.setMessage(Component.literal(ChainLabels.formatChainLabel(picked)));
                    host.rerollPreview();
                });
            }

            void render(GuiGraphics gfx, int cellLeft, int rowTop, int cellWidth, int rowHeight,
                        int mouseX, int mouseY, float partial) {
                int x = cellLeft + CELL_PAD;
                int iconY = rowTop + (rowHeight - ICON_SIZE) / 2;
                gfx.renderItem(iconForSelector(sel), x, iconY);
                int iconX = x;
                x += ICON_SIZE + CELL_GAP;

                int rightReserve = CELL_PAD + CHECKBOX_SIZE + CELL_GAP;
                if (deleteButton != null) rightReserve += DELETE_W + CELL_GAP;
                int dropdownAvail = cellLeft + cellWidth - rightReserve - x;
                int dropdownW = Math.max(48, dropdownAvail);
                int btnY = rowTop + (rowHeight - DROPDOWN_H) / 2;

                chainButton.setX(x);
                chainButton.setY(btnY);
                chainButton.setWidth(dropdownW);
                chainButton.render(gfx, mouseX, mouseY, partial);
                x += dropdownW + CELL_GAP;

                enabledBox.setX(x);
                enabledBox.setY(rowTop + (rowHeight - CHECKBOX_SIZE) / 2);
                enabledBox.render(gfx, mouseX, mouseY, partial);
                x += CHECKBOX_SIZE + CELL_GAP;

                if (deleteButton != null) {
                    deleteButton.setX(x);
                    deleteButton.setY(btnY);
                    deleteButton.render(gfx, mouseX, mouseY, partial);
                }

                boolean hoverIcon = mouseX >= iconX && mouseX < iconX + ICON_SIZE
                    && mouseY >= iconY && mouseY < iconY + ICON_SIZE;
                if (hoverIcon) {
                    gfx.renderComponentTooltip(Minecraft.getInstance().font,
                        List.of(Component.literal(sel.id().getPath()),
                                Component.literal(sel.appliesTo().toString())),
                        mouseX, mouseY);
                }
            }
        }
    }
}
