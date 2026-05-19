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
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal "Add custom selector" form — pick item(s), pick chain, done.
 * Reached from the {@code + Add custom selector} button at the bottom of
 * {@link CategorySelectorsScreen}.
 *
 * <p>Two rows:
 * <ul>
 *   <li>Items — opens {@link ItemPicker} in multi-material mode. User
 *       picks a kind (sword/axe/…); then a checkbox grid lets them
 *       include / exclude individual materials. Every material is
 *       checked by default; the resulting {@code List<ItemStack>}
 *       seeds one custom selector per item.</li>
 *   <li>Chain — a {@link ChainPicker} popup for the current category's
 *       tier. Both tiers on each resulting selector are set to this
 *       chain by default; the user can edit either later from the other
 *       category sub-menu.</li>
 * </ul>
 *
 * <p>On Add, one {@link EditBuffer#addCustomSelector(NameSelector)} call
 * fires per picked item. The supplied {@code onDone} callback fires once
 * after all are staged so the caller can refresh its list.
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
    private Button itemsButton;
    private Button chainButton;
    private Button addButton;
    private List<ItemStack> pickedItems = List.of();
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

        itemsButton = Button.builder(
            Component.literal(itemsButtonLabel()),
            b -> openItemPicker()
        ).bounds(fieldX, rowY, FIELD_W, 20).build();
        addRenderableWidget(itemsButton);

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

    private String itemsButtonLabel() {
        if (pickedItems.isEmpty()) return "Pick…";
        if (pickedItems.size() == 1) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(pickedItems.get(0).getItem());
            return id == null ? "Pick…" : id.toString();
        }
        return pickedItems.size() + " items";
    }

    private void openItemPicker() {
        activeItemPicker = new ItemPicker(width, height, new ItemPicker.Listener() {
            @Override public void onSpecific(ItemStack stack) {
                // Shouldn't fire in multi-mode, but treat as single-item selection just in case.
                pickedItems = List.of(stack.copy());
                itemsButton.setMessage(Component.literal(itemsButtonLabel()));
                activeItemPicker = null;
            }
            @Override public void onSpecificMulti(List<ItemStack> stacks) {
                pickedItems = new ArrayList<>(stacks.size());
                for (ItemStack s : stacks) pickedItems.add(s.copy());
                itemsButton.setMessage(Component.literal(itemsButtonLabel()));
                activeItemPicker = null;
            }
            @Override public void onRandomItem() {
                // No-op in multi-mode (Random cell isn't rendered) but guard anyway.
                activeItemPicker = null;
            }
            @Override public void onRandomMaterial(ItemPicker.Kind kind) {
                activeItemPicker = null;
            }
            @Override public void onCancelled() {
                activeItemPicker = null;
            }
        }, true);
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
        gfx.drawString(font, Component.literal("Items"), SIDE_PAD, rowY, 0xFFE0E0E0, false);
        if (!pickedItems.isEmpty()) {
            // Show up to 4 item icons to the right of the button as a peek.
            int iconX = SIDE_PAD + LABEL_W + FIELD_W + 4;
            int iconY = FIRST_ROW_Y + (20 - ICON_SIZE) / 2;
            int shown = Math.min(4, pickedItems.size());
            for (int i = 0; i < shown; i++) {
                gfx.renderItem(pickedItems.get(i), iconX + i * (ICON_SIZE + 2), iconY);
            }
        }
        rowY += ROW_H;
        gfx.drawString(font, Component.literal("Chain"), SIDE_PAD, rowY, 0xFFE0E0E0, false);

        if (!errorMessage.isEmpty()) {
            int errY = FIRST_ROW_Y + ROW_H * 2 + 32;
            gfx.drawCenteredString(font, Component.literal(errorMessage),
                width / 2, errY, 0xFFFF6060);
        }

        if (addButton != null) addButton.active = !pickedItems.isEmpty() && pickedChain.isPresent();
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
        if (pickedItems.isEmpty()) {
            errorMessage = "Pick at least one item";
            return;
        }
        if (pickedChain.isEmpty()) {
            errorMessage = "Pick a chain";
            return;
        }
        int added = 0;
        for (ItemStack stack : pickedItems) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) continue;
            ResourceLocation selectorId = ResourceLocation.fromNamespaceAndPath(
                "adventureitemnames", "custom_" + itemId.getPath());
            if (NameRegistry.shippedSelectors().containsKey(selectorId)) {
                // Skip silently — shipped already owns this id (rare for vanilla
                // sword/axe variants since shipped uses tags, but defensive).
                continue;
            }
            Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
            tiers.put(NameTier.PLAIN.key(), pickedChain.get());
            tiers.put(NameTier.ENCHANTED.key(), pickedChain.get());
            buffer.addCustomSelector(new NameSelector(selectorId, itemId, tiers));
            added++;
        }
        if (added == 0) {
            errorMessage = "All picked items collide with shipped selectors";
            return;
        }
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
