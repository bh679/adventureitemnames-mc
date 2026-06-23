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
 * Per-selector tier remapping table. One full row per selector with three
 * cycle-button dropdowns ({@code plain} / {@code enchanted} naming chain,
 * plus a {@code description} chain that fans out to both
 * {@code description_plain} and {@code description_enchanted} description
 * tiers) and an enable/disable checkbox. Saved overrides flow through
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

    /** One selector per list entry — full-width row with three dropdowns. */
    private static final int ICON_SIZE       = 16;
    private static final int CHECKBOX_SIZE   = 18;
    private static final int CELL_PAD        = 4;
    private static final int CELL_GAP        = 4;
    private static final int LIST_TOP        = 32;
    /** Per-entry row height: a single dropdown + a sliver of padding. */
    private static final int ENTRY_H         = 22;
    private static final int DROPDOWN_H      = 18;

    /** Synthetic tier key used by the picker dispatcher to recognise the description dropdown. */
    private static final String DESCRIPTION_TIER_KEY = "description";

    private final Screen parent;
    private final EditBuffer buffer;
    private SelectorList list;
    private PreviewPanel preview;
    private Button saveButton;
    /** Available chains for the picker: {@code Optional.empty()} = (none); else chain id. */
    private List<Optional<ResourceLocation>> chainCycle = List.of();
    private ChainPicker activePicker;
    private ConfirmDialog activeConfirm;
    private String openFingerprint;
    /** Item-tag ids known to the client at screen-open time. Used to render the ⚠ warning row. */
    private java.util.Set<ResourceLocation> loadedItemTagIds = java.util.Set.of();

    public SelectorsScreen(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.selectors.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    @Override
    protected void init() {
        chainCycle = buildChainCycle();
        loadedItemTagIds = new java.util.HashSet<>();
        BuiltInRegistries.ITEM.getTagNames().forEach(t -> loadedItemTagIds.add(t.location()));

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

        if (openFingerprint == null) openFingerprint = BufferFingerprint.of(buffer);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (activeConfirm != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeConfirm.render(gfx, mouseX, mouseY);
            return;
        }
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
        if (activeConfirm != null) { activeConfirm.mouseClicked(mouseX, mouseY, button); return true; }
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
        if (activeConfirm != null && activeConfirm.keyPressed(keyCode)) return true;
        if (activePicker != null && activePicker.keyPressed(keyCode)) return true;
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Open the chain picker for one tier on one selector. Called from a row's dropdown click. */
    void openChainPicker(ResourceLocation selectorId, String tierKey,
                         Optional<ResourceLocation> current, java.util.function.Consumer<Optional<ResourceLocation>> onPick) {
        String tierTitle;
        if (DESCRIPTION_TIER_KEY.equals(tierKey)) {
            tierTitle = "Description chain";
        } else if (NameTier.PLAIN.key().equals(tierKey)) {
            tierTitle = "Plain chain";
        } else {
            tierTitle = "Enchanted chain";
        }
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

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    /** Open the v3 custom-selector creation popup. */
    void openAddSelector() {
        Minecraft.getInstance().setScreen(new AddSelectorPopup(this, buffer));
    }

    /** True when the selector's {@code applies_to} tag is loaded — only user selectors render the warning. */
    boolean isTagLoaded(ResourceLocation tagId) {
        return loadedItemTagIds.contains(tagId);
    }

    /**
     * Every registered selector, ordered by {@link #SELECTOR_PATH_ORDER}
     * first and then alphabetically by id. Includes user-datapack
     * selectors (any namespace) so a third-party selector auto-appears
     * in the grid without a code change.
     */
    private List<NameSelector> orderedSelectors() {
        Map<ResourceLocation, NameSelector> remaining = new LinkedHashMap<>(NameRegistry.allSelectors());
        List<NameSelector> ordered = new ArrayList<>();
        for (String path : SELECTOR_PATH_ORDER) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("adventureitemnames", path);
            NameSelector sel = remaining.remove(id);
            if (sel != null) ordered.add(sel);
        }
        remaining.values().stream()
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
     * shield maps to itself. Unknown paths fall through to a paper icon so
     * user-defined selectors (with arbitrary id paths) still render visibly.
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
            ResourceLocation.fromNamespaceAndPath("minecraft", itemPath)).orElse(Items.PAPER);
        return new ItemStack(item);
    }

    static final class SelectorList extends ContainerObjectSelectionList<SelectorList.Entry> {

        SelectorList(Minecraft mc, int width, int height, int top,
                     List<NameSelector> selectors, SelectorsScreen host) {
            super(mc, width, height, top, ENTRY_H);
            for (NameSelector sel : selectors) {
                addEntry(new SelectorEntry(sel, host));
            }
            addEntry(new AddEntry(host));
        }

        @Override
        public int getRowWidth() { return width - 16; }

        @Override
        protected int getScrollbarPosition() { return width - 6; }

        /** Base type — subclassed by {@link SelectorEntry} (selectors) and {@link AddEntry} (+ row). */
        abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {}

        /** One list row = one selector cell stretched full-width. */
        static final class SelectorEntry extends Entry {

            private final Cell cell;

            SelectorEntry(NameSelector sel, SelectorsScreen host) {
                this.cell = new Cell(sel, host);
            }

            @Override
            public List<? extends NarratableEntry> narratables() { return cell.widgets(); }

            @Override
            public List<? extends GuiEventListener> children() { return cell.widgets(); }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                cell.render(gfx, rowLeft, rowTop, rowWidth, rowHeight, mouseX, mouseY, partial);
            }
        }

        /**
         * Bottom-of-list "+ Add custom selector" entry. v2 stops at a
         * placeholder click handler — letting the user define a tag →
         * chain mapping at runtime is v3 work (requires plumbing for
         * adding selectors at runtime + tag picker UI).
         */
        static final class AddEntry extends Entry {

            private final Button addButton;
            private final SelectorsScreen host;

            AddEntry(SelectorsScreen host) {
                this.host = host;
                this.addButton = Button.builder(
                    Component.literal("+ Add custom selector"),
                    b -> host.openAddSelector()
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
                // Divider line so the + row reads as a separate section, not just another row.
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

        /** One selector in a single-row layout: icon · plain · enchanted · description · enabled. */
        static final class Cell {

            private final NameSelector sel;
            private final SelectorsScreen host;
            private final Button plainButton;
            private final Button enchantedButton;
            private final Button descriptionButton;
            private final Checkbox enabledBox;

            Cell(NameSelector sel, SelectorsScreen host) {
                this.sel = sel;
                this.host = host;

                this.plainButton = makeNameCycleButton(NameTier.PLAIN.key());
                this.enchantedButton = makeNameCycleButton(NameTier.ENCHANTED.key());
                this.descriptionButton = makeDescriptionCycleButton();

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
                return List.of(plainButton, enchantedButton, descriptionButton, enabledBox);
            }

            private Button makeNameCycleButton(String tierKey) {
                Optional<ResourceLocation> initial = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                Button btn = Button.builder(
                    Component.literal(SelectorsScreen.formatChainLabel(initial)),
                    b -> openNamePicker(tierKey)
                ).bounds(0, 0, 60, DROPDOWN_H).build();
                btn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    NameTier.PLAIN.key().equals(tierKey)
                        ? Component.literal("Plain name chain")
                        : Component.literal("Enchanted name chain")));
                return btn;
            }

            private Button makeDescriptionCycleButton() {
                Optional<ResourceLocation> initial = host.buffer().effectiveDescriptionTierChain(sel);
                Button btn = Button.builder(
                    Component.literal(SelectorsScreen.formatChainLabel(initial)),
                    b -> openDescriptionPicker()
                ).bounds(0, 0, 60, DROPDOWN_H).build();
                btn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal("Description chain (applied to both plain and enchanted items)")));
                return btn;
            }

            private void openNamePicker(String tierKey) {
                Optional<ResourceLocation> current = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                host.openChainPicker(sel.id(), tierKey, current, picked -> {
                    host.buffer().setSelectorTier(sel.id(), tierKey, picked);
                    Button target = NameTier.PLAIN.key().equals(tierKey) ? plainButton : enchantedButton;
                    target.setMessage(Component.literal(SelectorsScreen.formatChainLabel(picked)));
                    host.rerollPreview();
                });
            }

            private void openDescriptionPicker() {
                Optional<ResourceLocation> current = host.buffer().effectiveDescriptionTierChain(sel);
                host.openChainPicker(sel.id(), DESCRIPTION_TIER_KEY, current, picked -> {
                    host.buffer().setSelectorDescriptionTier(sel.id(), picked);
                    descriptionButton.setMessage(Component.literal(SelectorsScreen.formatChainLabel(picked)));
                    host.rerollPreview();
                });
            }

            void render(GuiGraphics gfx, int cellLeft, int rowTop, int cellWidth, int rowHeight,
                        int mouseX, int mouseY, float partial) {
                int x = cellLeft + CELL_PAD;
                int iconY = rowTop + (rowHeight - ICON_SIZE) / 2;
                gfx.renderItem(iconForSelector(sel), x, iconY);
                int iconX = x;
                boolean isUser = NameRegistry.isUserSelector(sel.id());
                boolean tagMissing = isUser && !host.isTagLoaded(sel.appliesTo());
                if (tagMissing) {
                    gfx.drawString(Minecraft.getInstance().font,
                        Component.literal("⚠"), iconX + ICON_SIZE - 5, iconY + ICON_SIZE - 8, 0xFFFFAA55, false);
                }
                x += ICON_SIZE + CELL_GAP;

                // Three dropdowns share the available horizontal space evenly.
                int dropdownAvail = cellLeft + cellWidth - CELL_PAD - CHECKBOX_SIZE - CELL_GAP - x - 2 * CELL_GAP;
                int dropdownW = Math.max(24, dropdownAvail / 3);
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

                descriptionButton.setX(x);
                descriptionButton.setY(btnY);
                descriptionButton.setWidth(dropdownW);
                descriptionButton.render(gfx, mouseX, mouseY, partial);
                x += dropdownW + CELL_GAP;

                enabledBox.setX(x);
                enabledBox.setY(rowTop + (rowHeight - CHECKBOX_SIZE) / 2);
                enabledBox.render(gfx, mouseX, mouseY, partial);

                boolean hoverIcon = mouseX >= iconX && mouseX < iconX + ICON_SIZE
                    && mouseY >= iconY && mouseY < iconY + ICON_SIZE;
                if (hoverIcon) {
                    java.util.List<Component> tooltip = new java.util.ArrayList<>();
                    tooltip.add(Component.literal(sel.id().toString()));
                    tooltip.add(Component.literal("#" + sel.appliesTo()));
                    if (tagMissing) {
                        tooltip.add(Component.literal("⚠ Tag not loaded — selector inactive until tag is available")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
                    }
                    if (isUser) {
                        tooltip.add(Component.literal("(user-defined)")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                    }
                    gfx.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
                }
            }
        }
    }

}
