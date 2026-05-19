package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameSelector;
import games.brennan.adventureitemnames.api.NameTier;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    /** Selector path column — left-anchored, fixed text width. Tag shown as tooltip on hover. */
    private static final int COL_X_SELECTOR  = 8;
    private static final int COL_W_SELECTOR  = 72;
    /** Enabled checkbox column — fixed width on the right. */
    private static final int COL_W_ENABLED   = 24;
    private static final int GAP             = 6;
    private static final int HEADER_Y        = 44;
    private static final int LIST_TOP        = 58;

    /** Plain / Enchanted dropdown buttons split the remaining horizontal space evenly. */
    private static int dropdownX(int screenWidth, boolean enchanted) {
        int firstX = COL_X_SELECTOR + COL_W_SELECTOR + GAP;
        int dropdownW = dropdownWidth(screenWidth);
        if (!enchanted) return firstX;
        return firstX + dropdownW + GAP;
    }

    /** Each dropdown gets half of the space remaining after selector + enabled + gaps. */
    private static int dropdownWidth(int screenWidth) {
        int remaining = screenWidth - COL_X_SELECTOR - COL_W_SELECTOR - GAP - COL_W_ENABLED - GAP * 3;
        return Math.max(40, remaining / 2);
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

        gfx.drawString(font, "Selector", COL_X_SELECTOR,            HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Plain",    dropdownX(width, false),   HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "Enchant",  dropdownX(width, true),    HEADER_Y, 0xFFA0A0A0, false);
        gfx.drawString(font, "On",       enabledX(width) - 4,       HEADER_Y, 0xFFA0A0A0, false);

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

    static String formatChainLabel(Optional<ResourceLocation> chain) {
        return chain.map(rl -> rl.getPath()).orElse("(none)");
    }

    static final class SelectorList extends ContainerObjectSelectionList<SelectorList.Entry> {

        SelectorList(Minecraft mc, int width, int height, int top,
                     List<NameSelector> selectors, SelectorsScreen host) {
            super(mc, width, height, top, 24);
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
                int textY = rowTop + 8;
                int currentScreenWidth = host.width;

                String pathText = sel.id().getPath();
                String truncatedPath = Minecraft.getInstance().font.plainSubstrByWidth(pathText, COL_W_SELECTOR);
                if (!truncatedPath.equals(pathText) && truncatedPath.length() > 1) {
                    truncatedPath = Minecraft.getInstance().font.plainSubstrByWidth(pathText, COL_W_SELECTOR - 6) + "…";
                }
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(truncatedPath).withStyle(ChatFormatting.WHITE),
                    COL_X_SELECTOR, textY, 0xFFFFFFFF, false);

                int dropdownW = dropdownWidth(currentScreenWidth);
                plainButton.setX(dropdownX(currentScreenWidth, false));
                plainButton.setY(rowTop + 3);
                plainButton.setWidth(dropdownW);
                plainButton.render(gfx, mouseX, mouseY, partial);

                enchantedButton.setX(dropdownX(currentScreenWidth, true));
                enchantedButton.setY(rowTop + 3);
                enchantedButton.setWidth(dropdownW);
                enchantedButton.render(gfx, mouseX, mouseY, partial);

                enabledBox.setX(enabledX(currentScreenWidth));
                enabledBox.setY(rowTop + 3);
                enabledBox.render(gfx, mouseX, mouseY, partial);

                // Hover the selector path → render a tooltip with the item tag.
                boolean hoverSelectorText = mouseX >= COL_X_SELECTOR
                    && mouseX < COL_X_SELECTOR + COL_W_SELECTOR
                    && mouseY >= rowTop && mouseY < rowTop + rowHeight;
                if (hoverSelectorText) {
                    gfx.renderTooltip(Minecraft.getInstance().font,
                        Component.literal(sel.appliesTo().toString()), mouseX, mouseY);
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
