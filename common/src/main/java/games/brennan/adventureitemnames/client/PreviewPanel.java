package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-docked preview strip used on every config screen. Up to six
 * slots, each showing an item icon plus the name a roll produced;
 * clicking an icon opens a two-stage {@link ItemPicker} so the user can
 * switch the slot to any kind+material combo — useful for comparing
 * "Netherite Sword" vs "Wooden Sword" naming pressure.
 *
 * <p>The panel has two display modes controlled by a shared static
 * {@link #expanded} flag (default {@code false} = compact 2-slot strip,
 * 1 row; {@code true} = full 6-slot strip, 3 rows). A small toggle
 * button next to the 🎲 reroll button flips between them. The flag is
 * static so toggling on one config screen persists when navigating to
 * another sibling screen within the same session.
 *
 * <p>Each slot can be one of three configurations:
 * <ul>
 *   <li>{@code SLOT_SPECIFIC} — a fixed item stack.</li>
 *   <li>{@code SLOT_RANDOM_ITEM} — re-rolls kind + material on every name re-roll.</li>
 *   <li>{@code SLOT_RANDOM_MATERIAL} — kind locked, material re-rolls on every re-roll.</li>
 * </ul>
 * Random configs materialise to a fresh {@link ItemStack} per re-roll
 * via {@link #materialize}; the rolled-name display thus shows the
 * actual item that produced that line.
 */
@Environment(EnvType.CLIENT)
public final class PreviewPanel {

    public static final int HEIGHT_COLLAPSED = 24;
    public static final int HEIGHT_EXPANDED = 68;
    public static final int SLOT_COUNT = 6;
    private static final int COLLAPSED_SLOTS = 2;
    private static final int PADDING_X = 8;
    private static final int HEADER_H = 2;
    private static final int ICON_SIZE = 16;
    private static final int LINE_H = 22;
    private static final int TEXT_LINE_H = 9;
    private static final int TEXT_OFFSET_Y = 1;
    private static final int MAX_TEXT_LINES = 2;

    private static boolean expanded = false;

    /** Current panel height in pixels, given the shared expand state. */
    public static int currentHeight() { return expanded ? HEIGHT_EXPANDED : HEIGHT_COLLAPSED; }

    private static int visibleSlots() { return expanded ? SLOT_COUNT : COLLAPSED_SLOTS; }

    /** One slot's source configuration — feeds {@link #materialize}. */
    public sealed interface SlotConfig {
        record Specific(ItemStack stack) implements SlotConfig {}
        record RandomItem() implements SlotConfig {}
        record RandomMaterial(ItemPicker.Kind kind) implements SlotConfig {}
    }

    private final EditBuffer buffer;
    private final ResourceLocation forcePool;
    private final boolean gateByChance;
    private final Runnable onLayoutChanged;
    private final SlotConfig[] slotConfigs = new SlotConfig[SLOT_COUNT];
    private List<PreviewRoller.Result> results = List.of();
    private Button reroll;
    private Button toggle;
    private int screenWidth;
    private int screenHeight;
    private ItemPicker activePicker;

    public PreviewPanel(EditBuffer buffer, ResourceLocation forcePool) {
        this(buffer, forcePool, false, null);
    }

    /**
     * @param gateByChance when true, each preview slot first rolls
     *     against the effective per-tier chance; failed rolls render as
     *     {@code —}. Used by Spawn Chances + Selectors screens so chance
     *     edits are visible. v1 screens keep the un-gated default to
     *     preserve the always-shows-a-name UX.
     */
    public PreviewPanel(EditBuffer buffer, ResourceLocation forcePool, boolean gateByChance) {
        this(buffer, forcePool, gateByChance, null);
    }

    public PreviewPanel(EditBuffer buffer, ResourceLocation forcePool, Runnable onLayoutChanged) {
        this(buffer, forcePool, false, onLayoutChanged);
    }

    /**
     * Canonical constructor.
     *
     * @param onLayoutChanged invoked when the user toggles expand/collapse.
     *     Hosting screens pass {@code this::rebuildWidgets} so layout
     *     recomputes around the new panel height.
     */
    public PreviewPanel(EditBuffer buffer, ResourceLocation forcePool,
                        boolean gateByChance, Runnable onLayoutChanged) {
        this.buffer = buffer;
        this.forcePool = forcePool;
        this.gateByChance = gateByChance;
        this.onLayoutChanged = onLayoutChanged;
        for (int i = 0; i < SLOT_COUNT; i++) {
            slotConfigs[i] = new SlotConfig.Specific(PreviewRoller.DEFAULT_SAMPLES[i].copy());
        }
    }

    public void rebuild(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        if (results.isEmpty()) rerollNow();
        int top = screenHeight - currentHeight() + 2;
        reroll = Button.builder(Component.literal("🎲"), b -> rerollNow())
            .bounds(screenWidth - 32 - PADDING_X, top, 32, 16)
            .build();
        toggle = Button.builder(Component.literal(expanded ? "▼" : "▲"), b -> toggleExpanded())
            .bounds(screenWidth - 32 - PADDING_X - 16 - 4, top, 16, 16)
            .build();
    }

    public Button button() { return reroll; }
    public Button toggleButton() { return toggle; }

    private void toggleExpanded() {
        expanded = !expanded;
        if (onLayoutChanged != null) onLayoutChanged.run();
    }

    public void rerollNow() {
        RandomSource rng = pickRng();
        List<ItemStack> stacks = new ArrayList<>(SLOT_COUNT);
        List<Boolean> enchanted = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            stacks.add(materialize(slotConfigs[i], rng));
            enchanted.add(isSlotEnchanted(i));
        }
        results = PreviewRoller.rollBatch(stacks, enchanted, buffer, forcePool, gateByChance);
    }

    private void rerollSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) return;
        RandomSource rng = pickRng();
        ItemStack stack = materialize(slotConfigs[slot], rng);
        PreviewRoller.Result rolled = PreviewRoller.rollSingle(
            stack, isSlotEnchanted(slot), buffer, forcePool, gateByChance);
        List<PreviewRoller.Result> next = new ArrayList<>(results);
        while (next.size() <= slot) {
            next.add(new PreviewRoller.Result(materialize(slotConfigs[next.size()], rng), "—"));
        }
        next.set(slot, rolled);
        results = next;
    }

    /** Resolve a {@link SlotConfig} to a fresh {@link ItemStack}. Random configs re-roll each call. */
    private ItemStack materialize(SlotConfig config, RandomSource rng) {
        if (config instanceof SlotConfig.Specific s) return s.stack();
        if (config instanceof SlotConfig.RandomItem) return rollRandomItem(rng);
        if (config instanceof SlotConfig.RandomMaterial rm) return rollRandomMaterial(rm.kind(), rng);
        return ItemStack.EMPTY;
    }

    private ItemStack rollRandomItem(RandomSource rng) {
        ItemPicker.Kind[] all = ItemPicker.Kind.values();
        ItemPicker.Kind k = all[rng.nextInt(all.length)];
        if (k.isFixed()) return new ItemStack(k.iconItem());
        var mats = k.materials();
        return ItemPicker.resolve(k, mats.get(rng.nextInt(mats.size())));
    }

    private ItemStack rollRandomMaterial(ItemPicker.Kind k, RandomSource rng) {
        if (k.isFixed()) return new ItemStack(k.iconItem());
        var mats = k.materials();
        return ItemPicker.resolve(k, mats.get(rng.nextInt(mats.size())));
    }

    private RandomSource pickRng() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.random : RandomSource.create();
    }

    private boolean isSlotEnchanted(int slot) { return slot % 2 == 0; }

    /** Forward a screen-level click to the panel. Returns true when consumed. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activePicker != null) {
            activePicker.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (button != 0 || screenWidth == 0) return false;
        int y0 = screenHeight - currentHeight() + HEADER_H;
        int columnWidth = (screenWidth - PADDING_X * 2) / 2;
        int visible = visibleSlots();
        for (int i = 0; i < results.size() && i < visible; i++) {
            int col = i % 2;
            int row = i / 2;
            int iconX = PADDING_X + col * columnWidth;
            int iconY = y0 + row * LINE_H;
            if (mouseX >= iconX && mouseX < iconX + ICON_SIZE
                && mouseY >= iconY && mouseY < iconY + ICON_SIZE) {
                openPickerForSlot(i);
                return true;
            }
        }
        return false;
    }

    /** Forward a screen-level key press. Returns true when consumed. */
    public boolean keyPressed(int keyCode) {
        if (activePicker == null) return false;
        return activePicker.keyPressed(keyCode);
    }

    private void openPickerForSlot(int slot) {
        activePicker = new ItemPicker(screenWidth, screenHeight, new ItemPicker.Listener() {
            @Override public void onSpecific(ItemStack stack) {
                slotConfigs[slot] = new SlotConfig.Specific(stack);
                activePicker = null;
                rerollSlot(slot);
            }
            @Override public void onRandomItem() {
                slotConfigs[slot] = new SlotConfig.RandomItem();
                activePicker = null;
                rerollSlot(slot);
            }
            @Override public void onRandomMaterial(ItemPicker.Kind kind) {
                slotConfigs[slot] = new SlotConfig.RandomMaterial(kind);
                activePicker = null;
                rerollSlot(slot);
            }
            @Override public void onCancelled() {
                activePicker = null;
            }
        });
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (screenWidth == 0) return;
        int y0 = screenHeight - currentHeight();
        gfx.fill(0, y0, screenWidth, screenHeight, 0xCC101010);
        gfx.fill(0, y0, screenWidth, y0 + 1, 0xFF606060);

        int rowsStartY = y0 + HEADER_H;
        int columnWidth = (screenWidth - PADDING_X * 2) / 2;
        int visible = visibleSlots();
        int hoveredSlot = -1;
        for (int i = 0; i < results.size() && i < visible; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = PADDING_X + col * columnWidth;
            int y = rowsStartY + row * LINE_H;
            boolean iconHover = mouseX >= x && mouseX < x + ICON_SIZE
                && mouseY >= y && mouseY < y + ICON_SIZE;
            if (iconHover) hoveredSlot = i;

            PreviewRoller.Result r = results.get(i);
            if (iconHover) gfx.fill(x - 1, y - 1, x + ICON_SIZE + 1, y + ICON_SIZE + 1, 0x40FFFFFF);
            gfx.renderItem(r.icon(), x, y);

            if (slotConfigs[i] instanceof SlotConfig.RandomItem
                || slotConfigs[i] instanceof SlotConfig.RandomMaterial) {
                gfx.fill(x + ICON_SIZE - 5, y + ICON_SIZE - 5, x + ICON_SIZE, y + ICON_SIZE, 0xFF6A4A8A);
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal("?"), x + ICON_SIZE - 4, y + ICON_SIZE - 5, 0xFFFFFFFF, false);
            }

            int textX = x + ICON_SIZE + 4;
            int textMaxWidth = columnWidth - ICON_SIZE - 8;
            if (col == 1 && row == 0) textMaxWidth -= 60;
            List<FormattedCharSequence> trimmed = Minecraft.getInstance().font.split(
                Component.literal(r.name()), textMaxWidth);
            int lines = Math.min(MAX_TEXT_LINES, trimmed.size());
            for (int li = 0; li < lines; li++) {
                gfx.drawString(Minecraft.getInstance().font, trimmed.get(li),
                    textX, y + TEXT_OFFSET_Y + li * TEXT_LINE_H, 0xFFE0E0E0, false);
            }
        }

        if (hoveredSlot >= 0 && hoveredSlot < results.size() && activePicker == null) {
            gfx.renderTooltip(Minecraft.getInstance().font,
                results.get(hoveredSlot).icon(), mouseX, mouseY);
        }

        if (activePicker != null) {
            activePicker.render(gfx, mouseX, mouseY);
        }
    }
}
