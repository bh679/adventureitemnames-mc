package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameSelector;
import games.brennan.adventureitemnames.api.NameTier;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal "Add custom selector" form — pick an item, pick a chain, done.
 * Reached from the {@code + Add custom selector} button at the bottom of
 * {@link CategorySelectorsScreen}.
 *
 * <p>Two rows:
 * <ul>
 *   <li>Item — opens {@link ItemPicker} to pick a vanilla item from
 *       the existing Kind/Material grid. The picked {@link ItemStack}
 *       becomes the selector's {@code applies_to} (matched by item id
 *       in {@code NameRegistry#selectorMatches}).</li>
 *   <li>Chain — a {@link ChainPicker} popup for the current category's
 *       tier. Both tiers on the resulting selector are set to this
 *       chain by default; the user can edit either later from the other
 *       category sub-menu.</li>
 * </ul>
 *
 * <p>On Add, the new selector is staged via
 * {@link EditBuffer#addCustomSelector(NameSelector)} and the supplied
 * {@code onDone} callback fires so the caller can refresh its list.
 */
@Environment(EnvType.CLIENT)
public final class AddSelectorScreen extends Screen {

    private static final int ROW_H = 28;
    private static final int LABEL_W = 70;
    private static final int FIELD_W = 200;
    private static final int ICON_SIZE = 16;
    private static final int SIDE_PAD = 16;
    private static final int FIRST_ROW_Y = 50;

    private final Screen parent;
    private final EditBuffer buffer;
    private final NameTier tier;
    private final Runnable onDone;
    private Button itemButton;
    private Button chainButton;
    private Button addButton;
    private ItemStack pickedItem = ItemStack.EMPTY;
    private Optional<ResourceLocation> pickedChain = Optional.empty();
    private List<Optional<ResourceLocation>> chainCycle = List.of();
    private ItemPicker activeItemPicker;
    private ChainPicker activeChainPicker;
    private String errorMessage = "";

    public AddSelectorScreen(Screen parent, EditBuffer buffer, NameTier tier, Runnable onDone) {
        super(Component.literal("Add custom selector"));
        this.parent = parent;
        this.buffer = buffer;
        this.tier = tier;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        chainCycle = buildChainCycle();

        int fieldX = SIDE_PAD + LABEL_W;
        int rowY = FIRST_ROW_Y;

        itemButton = Button.builder(
            Component.literal(itemButtonLabel()),
            b -> openItemPicker()
        ).bounds(fieldX, rowY, FIELD_W, 20).build();
        addRenderableWidget(itemButton);

        rowY += ROW_H;
        chainButton = Button.builder(
            Component.literal(ChainLabels.formatChainLabel(pickedChain)),
            b -> openChainPicker()
        ).bounds(fieldX, rowY, FIELD_W, 20).build();
        addRenderableWidget(chainButton);

        rowY += ROW_H + 12;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.cancel"),
            b -> onClose()
        ).bounds(SIDE_PAD, rowY, 90, 20).build());

        addButton = Button.builder(
            Component.literal("Add"),
            b -> submit()
        ).bounds(SIDE_PAD + LABEL_W + FIELD_W - 90, rowY, 90, 20).build();
        addRenderableWidget(addButton);
    }

    private String itemButtonLabel() {
        if (pickedItem == null || pickedItem.isEmpty()) return "Pick…";
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(pickedItem.getItem());
        return id == null ? "Pick…" : id.toString();
    }

    private void openItemPicker() {
        activeItemPicker = new ItemPicker(width, height, new ItemPicker.Listener() {
            @Override public void onSpecific(ItemStack stack) {
                pickedItem = stack.copy();
                itemButton.setMessage(Component.literal(itemButtonLabel()));
                activeItemPicker = null;
            }
            @Override public void onRandomItem() {
                // No meaningful interpretation here — the selector needs a concrete item.
                activeItemPicker = null;
            }
            @Override public void onRandomMaterial(ItemPicker.Kind kind) {
                // Treat as picking the iron variant of that kind, so the form still
                // produces a concrete item.
                pickedItem = new ItemStack(kind.iconItem());
                itemButton.setMessage(Component.literal(itemButtonLabel()));
                activeItemPicker = null;
            }
            @Override public void onCancelled() {
                activeItemPicker = null;
            }
        });
    }

    private void openChainPicker() {
        String tierTitle = tier == NameTier.PLAIN ? "Plain chain" : "Enchanted chain";
        activeChainPicker = new ChainPicker(width, height, tierTitle, chainCycle, pickedChain,
            new ChainPicker.Listener() {
                @Override public void onPicked(Optional<ResourceLocation> chain) {
                    pickedChain = chain;
                    chainButton.setMessage(Component.literal(ChainLabels.formatChainLabel(chain)));
                    activeChainPicker = null;
                }
                @Override public void onCancelled() {
                    activeChainPicker = null;
                }
            });
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (activeItemPicker != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeItemPicker.render(gfx, mouseX, mouseY);
            return;
        }
        if (activeChainPicker != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeChainPicker.render(gfx, mouseX, mouseY);
            return;
        }
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);

        int rowY = FIRST_ROW_Y + 6;
        gfx.drawString(font, Component.literal("Item"), SIDE_PAD, rowY, 0xFFE0E0E0, false);
        if (pickedItem != null && !pickedItem.isEmpty()) {
            // Show a small icon to the right of the item button for a visual confirm.
            int iconX = SIDE_PAD + LABEL_W + FIELD_W + 4;
            int iconY = FIRST_ROW_Y + (20 - ICON_SIZE) / 2;
            gfx.renderItem(pickedItem, iconX, iconY);
        }
        rowY += ROW_H;
        gfx.drawString(font, Component.literal("Chain"), SIDE_PAD, rowY, 0xFFE0E0E0, false);

        if (!errorMessage.isEmpty()) {
            int errY = FIRST_ROW_Y + ROW_H * 2 + 32;
            gfx.drawCenteredString(font, Component.literal(errorMessage),
                width / 2, errY, 0xFFFF6060);
        }

        if (addButton != null) addButton.active = pickedItem != null && !pickedItem.isEmpty() && pickedChain.isPresent();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeItemPicker != null) {
            activeItemPicker.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (activeChainPicker != null) {
            activeChainPicker.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeChainPicker != null && activeChainPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeItemPicker != null && activeItemPicker.keyPressed(keyCode)) return true;
        if (activeChainPicker != null && activeChainPicker.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void submit() {
        errorMessage = "";
        if (pickedItem == null || pickedItem.isEmpty()) {
            errorMessage = "Pick an item first";
            return;
        }
        if (pickedChain.isEmpty()) {
            errorMessage = "Pick a chain";
            return;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(pickedItem.getItem());
        if (itemId == null) {
            errorMessage = "Picked item is not registered";
            return;
        }
        // Auto-derive selector id from the item id. Namespace stays adventureitemnames
        // so user customs don't collide with shipped ones (e.g. minecraft:iron_sword →
        // adventureitemnames:custom_iron_sword).
        ResourceLocation selectorId = ResourceLocation.fromNamespaceAndPath(
            "adventureitemnames", "custom_" + itemId.getPath());
        if (NameRegistry.shippedSelectors().containsKey(selectorId)) {
            errorMessage = "A shipped selector already uses that id";
            return;
        }

        // Set BOTH tiers to the picked chain — the user picked one chain
        // from one category sub-menu; defaulting the other tier to the
        // same chain means the item gets named in both states. They can
        // edit the other tier from the other sub-menu later.
        Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
        tiers.put(NameTier.PLAIN.key(), pickedChain.get());
        tiers.put(NameTier.ENCHANTED.key(), pickedChain.get());

        NameSelector sel = new NameSelector(selectorId, itemId, tiers);
        buffer.addCustomSelector(sel);

        if (onDone != null) onDone.run();
        Minecraft.getInstance().setScreen(parent);
    }

    /** Same cycle as the category screens: (none) first then alphabetical chain ids. */
    private static List<Optional<ResourceLocation>> buildChainCycle() {
        List<ResourceLocation> sorted = new ArrayList<>(NameRegistry.allChains().keySet());
        sorted.sort(Comparator.comparing(ResourceLocation::toString));
        List<Optional<ResourceLocation>> out = new ArrayList<>(sorted.size() + 1);
        out.add(Optional.empty());
        for (ResourceLocation rl : sorted) out.add(Optional.of(rl));
        return Collections.unmodifiableList(out);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
