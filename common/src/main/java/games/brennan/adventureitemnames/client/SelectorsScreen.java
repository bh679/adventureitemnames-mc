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

    /** Fixed display order for the 10 supported selector paths. */
    private static final List<String> SELECTOR_PATH_ORDER = List.of(
        "sword", "axe", "pickaxe", "shovel", "hoe",
        "helmet", "chestplate", "leggings", "boots", "shield");

    /** Selector icon column — 16x16 vanilla item icon. Path + tag shown as tooltip on hover. */
    private static final int COL_X_SELECTOR  = 8;
    private static final int COL_W_SELECTOR  = 18;
    private static final int ICON_SIZE       = 16;
    /** "P" / "E" tier label column — single-char prefix to the left of each dropdown. */
    private static final int COL_W_TIER      = 10;
    /** Enabled checkbox column — fixed width on the right. */
    private static final int COL_W_ENABLED   = 24;
    private static final int GAP             = 6;
    private static final int LIST_TOP        = 32;
    /** Per-entry row height: 2 stacked dropdowns + a sliver of padding. */
    private static final int ENTRY_H         = 44;
    private static final int DROPDOWN_H      = 18;

    /** Dropdown starts after icon + gap + tier label + small gap. */
    private static int dropdownX(int screenWidth) {
        return COL_X_SELECTOR + COL_W_SELECTOR + GAP + COL_W_TIER + 2;
    }

    /** Dropdown fills the space between its start x and the enabled checkbox. */
    private static int dropdownWidth(int screenWidth) {
        int w = enabledX(screenWidth) - GAP - dropdownX(screenWidth);
        return Math.max(40, w);
    }

    /** Enabled checkbox sits at the right edge with a small padding. */
    private static int enabledX(int screenWidth) {
        return screenWidth - COL_W_ENABLED - GAP;
    }

    private final Screen parent;
    private final EditBuffer buffer;
    private SelectorList list;
    private PreviewPanel preview;
    private Button saveButton;
    /** Cached cycle list — {@code Optional.empty()} = (none); else chain id. Refreshed on each {@link #init}. */
    private List<Optional<ResourceLocation>> chainCycle = List.of();

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

    int indexOfChain(Optional<ResourceLocation> current) {
        for (int i = 0; i < chainCycle.size(); i++) {
            if (chainCycle.get(i).equals(current)) return i;
        }
        return 0;
    }

    Optional<ResourceLocation> cycleChain(Optional<ResourceLocation> current, boolean backwards) {
        if (chainCycle.isEmpty()) return current;
        int idx = indexOfChain(current);
        int next = backwards
            ? (idx - 1 + chainCycle.size()) % chainCycle.size()
            : (idx + 1) % chainCycle.size();
        return chainCycle.get(next);
    }

    /**
     * Compact display name for a chain id: drops the {@code adventureitemnames:}
     * namespace prefix, strips the literal word {@code name} that's often used
     * as a filler in path components (so {@code weapon_name_short} reads as
     * {@code weapon short}), and replaces remaining underscores with spaces.
     */
    static String formatChainLabel(Optional<ResourceLocation> chain) {
        if (chain.isEmpty()) return "(none)";
        ResourceLocation rl = chain.get();
        String path = rl.getPath()
            .replaceFirst("_name_", "_")
            .replaceFirst("_name$", "")
            .replaceFirst("^name_", "")
            .replace('_', ' ');
        if (path.isBlank()) path = rl.getPath();
        if (!"adventureitemnames".equals(rl.getNamespace())) {
            return rl.getNamespace() + ":" + path;
        }
        return path;
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
            for (NameSelector sel : selectors) addEntry(new Entry(sel, host));
        }

        @Override
        public int getRowWidth() { return width - 32; }

        @Override
        protected int getScrollbarPosition() { return width - 12; }

        static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final NameSelector sel;
            private final SelectorsScreen host;
            private final Button plainButton;
            private final Button enchantedButton;
            private final Checkbox enabledBox;

            Entry(NameSelector sel, SelectorsScreen host) {
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

            private Button makeCycleButton(String tierKey) {
                Optional<ResourceLocation> initial = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                return new CycleButton(0, 0, 140, 18,
                    Component.literal(SelectorsScreen.formatChainLabel(initial)),
                    (backwards) -> onCycle(tierKey, backwards));
            }

            private void onCycle(String tierKey, boolean backwards) {
                Optional<ResourceLocation> current = host.buffer().effectiveTierChain(
                    sel.id(), tierKey, sel.tiers().get(tierKey));
                Optional<ResourceLocation> next = host.cycleChain(current, backwards);
                host.buffer().setSelectorTier(sel.id(), tierKey, next);
                Button target = NameTier.PLAIN.key().equals(tierKey) ? plainButton : enchantedButton;
                target.setMessage(Component.literal(SelectorsScreen.formatChainLabel(next)));
                host.rerollPreview();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(plainButton, enchantedButton, enabledBox);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(plainButton, enchantedButton, enabledBox);
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int currentScreenWidth = host.width;

                int iconX = COL_X_SELECTOR;
                int iconY = rowTop + (rowHeight - ICON_SIZE) / 2;
                gfx.renderItem(iconForSelector(sel), iconX, iconY);

                int plainRowY = rowTop + 3;
                int enchantedRowY = rowTop + 3 + DROPDOWN_H + 2;
                int tierLabelX = COL_X_SELECTOR + COL_W_SELECTOR + GAP;
                int dropdownXVal = dropdownX(currentScreenWidth);
                int dropdownW = dropdownWidth(currentScreenWidth);

                gfx.drawString(Minecraft.getInstance().font, "P",
                    tierLabelX, plainRowY + 5, 0xFFA0A0A0, false);
                plainButton.setX(dropdownXVal);
                plainButton.setY(plainRowY);
                plainButton.setWidth(dropdownW);
                plainButton.render(gfx, mouseX, mouseY, partial);

                gfx.drawString(Minecraft.getInstance().font, "E",
                    tierLabelX, enchantedRowY + 5, 0xFFA0A0A0, false);
                enchantedButton.setX(dropdownXVal);
                enchantedButton.setY(enchantedRowY);
                enchantedButton.setWidth(dropdownW);
                enchantedButton.render(gfx, mouseX, mouseY, partial);

                enabledBox.setX(enabledX(currentScreenWidth));
                enabledBox.setY(rowTop + (rowHeight - 18) / 2);
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

    /**
     * {@link Button} that emits a "cycle" callback with a {@code backwards}
     * flag (true on right-click / shift-click). Used by the chain
     * dropdowns to walk the cycle without needing an overlay widget.
     */
    private static final class CycleButton extends Button {
        private final java.util.function.Consumer<Boolean> onCycle;

        CycleButton(int x, int y, int w, int h, Component label,
                    java.util.function.Consumer<Boolean> onCycle) {
            super(x, y, w, h, label, b -> {}, Button.DEFAULT_NARRATION);
            this.onCycle = onCycle;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.isHovered() || !this.active || !this.visible) return false;
            if (button == 0) {
                boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
                onCycle.accept(shift);
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            if (button == 1) {
                onCycle.accept(true);
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            return false;
        }
    }
}
