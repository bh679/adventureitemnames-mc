package games.brennan.adventureitemnames.client;

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
 * v2 — per-selector tier remapping table. One row per selector exposed
 * by the registry (filtered to the 10 vanilla item-class selectors v2
 * supports). Each row offers two cycle-button dropdowns for the
 * {@code plain} / {@code enchanted} chain id plus an enable/disable
 * checkbox. Saved overrides flow through
 * {@link NamingConfig#effectiveTierChain} so {@link NameRegistry}'s
 * shipped JSON never gets mutated.
 *
 * <p>Cycle order for the dropdowns: {@code (none)} first, then every
 * registered chain id sorted alphabetically. Right-click / shift-click
 * cycles backwards.
 */
@Environment(EnvType.CLIENT)
public final class SelectorsScreen extends Screen {

    /** Fixed display order for the supported selector paths. */
    private static final List<String> SELECTOR_PATH_ORDER = List.of(
        "sword", "axe", "pickaxe", "shovel", "hoe", "bow",
        "helmet", "chestplate", "leggings", "boots", "shield");

    /** Two selector cells per list entry — laid out side-by-side in a 2-column grid. */
    private static final int ICON_SIZE       = 16;
    private static final int CHECKBOX_SIZE   = 18;
    private static final int CELL_PAD        = 4;
    private static final int CELL_GAP        = 4;
    private static final int GAP_BETWEEN     = 6;
    private static final int LIST_TOP        = 32;
    /** Per-entry row height: a single dropdown + a sliver of padding. */
    private static final int ENTRY_H         = 22;
    private static final int DROPDOWN_H      = 18;

    private final Screen parent;
    private final EditBuffer buffer;
    private SelectorList list;
    private PreviewPanel preview;
    private Button saveButton;
    /** Available chains for the picker: {@code Optional.empty()} = (none); else chain id. */
    private List<Optional<ResourceLocation>> chainCycle = List.of();
    private ChainPicker activePicker;

    public SelectorsScreen(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.selectors.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    @Override
    protected void init() {
        chainCycle = buildChainCycle();

        List<NameSelector> selectors = orderedSelectors();
        int listBottom = height - PreviewPanel.HEIGHT - 32;
        list = new SelectorList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, selectors, this);
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
        if (activePicker != null) {
            // Modal picker is open — skip the underlying widget render so item
            // icons + button tooltips don't bleed through the popup. The picker
            // already draws its own dim backdrop.
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

    /** Open the chain picker for one tier on one selector. Called from a row's dropdown click. */
    void openChainPicker(ResourceLocation selectorId, String tierKey,
                         Optional<ResourceLocation> current, java.util.function.Consumer<Optional<ResourceLocation>> onPick) {
        String tierTitle = NameTier.PLAIN.key().equals(tierKey) ? "Plain chain" : "Enchanted chain";
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

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    EditBuffer buffer() { return buffer; }

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    /** Filtered registered selectors in the fixed display order. Missing entries are skipped. */
    private List<NameSelector> orderedSelectors() {
        Map<String, NameSelector> byPath = new LinkedHashMap<>();
        for (var entry : NameRegistry.allSelectors().entrySet()) {
            ResourceLocation id = entry.getKey();
            if (!"adventureitemnames".equals(id.getNamespace())) continue;
            byPath.put(id.getPath(), entry.getValue());
        }
        List<NameSelector> ordered = new ArrayList<>();
        for (String path : SELECTOR_PATH_ORDER) {
            NameSelector sel = byPath.get(path);
            if (sel != null) ordered.add(sel);
        }
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
     * Per-word abbreviations applied after dropping {@code name} and
     * splitting on underscores — squeezes common long tokens into
     * narrow dropdown buttons. Unknown tokens pass through unchanged.
     */
    private static final java.util.Map<String, String> WORD_ABBREVS = java.util.Map.of(
        "weapon", "wep",
        "material", "mat",
        "element", "elem",
        "combinations", "combos",
        "combination", "combo");

    /**
     * Compact display name for a chain id (for use in the dropdown
     * <em>button</em> — limited horizontal space): drops the
     * {@code adventureitemnames:} namespace prefix, strips the literal
     * word {@code name} that's often used as a filler in path
     * components (so {@code weapon_name_short} reads as
     * {@code wep short}), and replaces remaining underscores with
     * spaces.
     */
    static String formatChainLabel(Optional<ResourceLocation> chain) {
        if (chain.isEmpty()) return "(none)";
        ResourceLocation rl = chain.get();
        String stripped = rl.getPath()
            .replaceFirst("_name_", "_")
            .replaceFirst("_name$", "")
            .replaceFirst("^name_", "");
        if (stripped.isBlank()) stripped = rl.getPath();
        String[] words = stripped.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(WORD_ABBREVS.getOrDefault(words[i], words[i]));
        }
        return prefixNamespace(rl, sb.toString());
    }

    /**
     * Full display name for a chain id (for use in the popup picker
     * list — plenty of horizontal space, prefer readability): replaces
     * underscores with spaces, keeps the literal word {@code name}, no
     * abbreviations. So {@code weapon_name_short} reads as
     * {@code weapon name short}.
     */
    static String formatChainLabelFull(Optional<ResourceLocation> chain) {
        if (chain.isEmpty()) return "(none)";
        ResourceLocation rl = chain.get();
        return prefixNamespace(rl, rl.getPath().replace('_', ' '));
    }

    private static String prefixNamespace(ResourceLocation rl, String label) {
        if (!"adventureitemnames".equals(rl.getNamespace())) {
            return rl.getNamespace() + ":" + label;
        }
        return label;
    }

    /**
     * Map a selector path to a representative vanilla {@link ItemStack}.
     * Tool / armor selectors use the iron variant (so the icon is colour-rich);
     * shield maps to itself. Unknown paths fall through to {@link Items#AIR}.
     */
    private static ItemStack iconForSelector(NameSelector sel) {
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
                     List<NameSelector> selectors, SelectorsScreen host) {
            super(mc, width, height, top, ENTRY_H);
            for (int i = 0; i < selectors.size(); i += 2) {
                NameSelector left = selectors.get(i);
                NameSelector right = i + 1 < selectors.size() ? selectors.get(i + 1) : null;
                addEntry(new Entry(left, right, host));
            }
        }

        @Override
        public int getRowWidth() { return width - 16; }

        @Override
        protected int getScrollbarPosition() { return width - 6; }

        /** One list row = two selector cells side-by-side. */
        static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final Cell left;
            private final Cell right;
            private final SelectorsScreen host;

            Entry(NameSelector leftSel, NameSelector rightSel, SelectorsScreen host) {
                this.host = host;
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

        /** One selector in the 2-column grid: icon · plain · enchanted · enabled. */
        static final class Cell {

            private final NameSelector sel;
            private final SelectorsScreen host;
            private final Button plainButton;
            private final Button enchantedButton;
            private final Checkbox enabledBox;

            Cell(NameSelector sel, SelectorsScreen host) {
                this.sel = sel;
                this.host = host;

                this.plainButton = makeCycleButton(NameTier.PLAIN.key());
                this.enchantedButton = makeCycleButton(NameTier.ENCHANTED.key());

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
            }

            List<? extends net.minecraft.client.gui.components.AbstractWidget> widgets() {
                return List.of(plainButton, enchantedButton, enabledBox);
            }

            private Button makeCycleButton(String tierKey) {
                Optional<ResourceLocation> initial = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                Component tooltipLabel = tooltipForTier(tierKey);
                Button btn = Button.builder(
                    Component.literal(SelectorsScreen.formatChainLabel(initial)),
                    b -> openPicker(tierKey)
                ).bounds(0, 0, 60, DROPDOWN_H).build();
                btn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltipLabel));
                return btn;
            }

            private static Component tooltipForTier(String tierKey) {
                if (NameTier.PLAIN.key().equals(tierKey)) return Component.literal("Plain chain");
                return Component.literal("Enchanted chain");
            }

            private void openPicker(String tierKey) {
                Optional<ResourceLocation> current = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                host.openChainPicker(sel.id(), tierKey, current, picked -> {
                    host.buffer().setSelectorTier(sel.id(), tierKey, picked);
                    Button target = NameTier.PLAIN.key().equals(tierKey) ? plainButton : enchantedButton;
                    target.setMessage(Component.literal(SelectorsScreen.formatChainLabel(picked)));
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

                int dropdownAvail = cellLeft + cellWidth - CELL_PAD - CHECKBOX_SIZE - CELL_GAP - x - CELL_GAP;
                int dropdownW = Math.max(24, dropdownAvail / 2);
                int btnY = rowTop + (rowHeight - DROPDOWN_H) / 2;

                plainButton.setX(x);
                plainButton.setY(btnY);
                plainButton.setWidth(dropdownW);
                plainButton.render(gfx, mouseX, mouseY, partial);
                x += dropdownW + CELL_GAP;

                enchantedButton.setX(x);
                enchantedButton.setY(btnY);
                enchantedButton.setWidth(dropdownW);
                enchantedButton.render(gfx, mouseX, mouseY, partial);
                x += dropdownW + CELL_GAP;

                enabledBox.setX(x);
                enabledBox.setY(rowTop + (rowHeight - CHECKBOX_SIZE) / 2);
                enabledBox.render(gfx, mouseX, mouseY, partial);

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
