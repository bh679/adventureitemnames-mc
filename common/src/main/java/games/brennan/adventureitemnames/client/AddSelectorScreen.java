package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameSelector;
import games.brennan.adventureitemnames.api.NameTier;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
import java.util.function.Consumer;

/**
 * Simple form screen to define a new user-defined {@link NameSelector}.
 * Reached from the {@code + Add custom selector} button at the bottom of
 * {@link CategorySelectorsScreen}.
 *
 * <p>Three input rows:
 * <ul>
 *   <li>Selector id — short path string, namespaced
 *       {@code adventureitemnames:} by default.</li>
 *   <li>Item tag — the {@code applies_to} target. Defaults to
 *       {@code minecraft:} namespace if no colon supplied.</li>
 *   <li>Plain chain + Enchanted chain — dropdown buttons that open a
 *       {@link ChainPicker} popup. Required fields; the form refuses to
 *       submit until both are picked.</li>
 * </ul>
 *
 * <p>On OK, the new selector is staged via
 * {@link EditBuffer#addCustomSelector(NameSelector)} and the supplied
 * {@code onDone} callback fires so the caller can refresh its list.
 */
@Environment(EnvType.CLIENT)
public final class AddSelectorScreen extends Screen {

    private static final int ROW_H = 26;
    private static final int LABEL_W = 90;
    private static final int FIELD_W = 200;
    private static final int SIDE_PAD = 16;
    private static final int FIRST_ROW_Y = 50;

    private final Screen parent;
    private final EditBuffer buffer;
    private final Runnable onDone;
    private EditBox idField;
    private EditBox tagField;
    private Button plainChainButton;
    private Button enchantedChainButton;
    private Button okButton;
    private Optional<ResourceLocation> plainChain = Optional.empty();
    private Optional<ResourceLocation> enchantedChain = Optional.empty();
    private List<Optional<ResourceLocation>> chainCycle = List.of();
    private ChainPicker activePicker;
    private String errorMessage = "";

    public AddSelectorScreen(Screen parent, EditBuffer buffer, Runnable onDone) {
        super(Component.literal("Add custom selector"));
        this.parent = parent;
        this.buffer = buffer;
        this.onDone = onDone;
    }

    @Override
    protected void init() {
        chainCycle = buildChainCycle();

        int rowY = FIRST_ROW_Y;
        int fieldX = SIDE_PAD + LABEL_W;

        idField = new EditBox(font, fieldX, rowY, FIELD_W, 18, Component.literal("selector id"));
        idField.setMaxLength(64);
        idField.setHint(Component.literal("e.g. custom_trident"));
        addRenderableWidget(idField);

        rowY += ROW_H;
        tagField = new EditBox(font, fieldX, rowY, FIELD_W, 18, Component.literal("item tag"));
        tagField.setMaxLength(96);
        tagField.setHint(Component.literal("e.g. minecraft:tridents"));
        addRenderableWidget(tagField);

        rowY += ROW_H;
        plainChainButton = Button.builder(
            Component.literal(ChainLabels.formatChainLabel(plainChain)),
            b -> openChainPicker(true)
        ).bounds(fieldX, rowY, FIELD_W, 18).build();
        addRenderableWidget(plainChainButton);

        rowY += ROW_H;
        enchantedChainButton = Button.builder(
            Component.literal(ChainLabels.formatChainLabel(enchantedChain)),
            b -> openChainPicker(false)
        ).bounds(fieldX, rowY, FIELD_W, 18).build();
        addRenderableWidget(enchantedChainButton);

        rowY += ROW_H + 8;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.cancel"),
            b -> onClose()
        ).bounds(SIDE_PAD, rowY, 90, 20).build());

        okButton = Button.builder(
            Component.literal("Add"),
            b -> submit()
        ).bounds(SIDE_PAD + LABEL_W + FIELD_W - 90, rowY, 90, 20).build();
        addRenderableWidget(okButton);
    }

    private void openChainPicker(boolean isPlain) {
        Optional<ResourceLocation> current = isPlain ? plainChain : enchantedChain;
        String title = isPlain ? "Plain chain" : "Enchanted chain";
        activePicker = new ChainPicker(width, height, title, chainCycle, current,
            new ChainPicker.Listener() {
                @Override public void onPicked(Optional<ResourceLocation> chain) {
                    activePicker = null;
                    if (isPlain) {
                        plainChain = chain;
                        plainChainButton.setMessage(Component.literal(ChainLabels.formatChainLabel(chain)));
                    } else {
                        enchantedChain = chain;
                        enchantedChainButton.setMessage(Component.literal(ChainLabels.formatChainLabel(chain)));
                    }
                }
                @Override public void onCancelled() {
                    activePicker = null;
                }
            });
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

        int rowY = FIRST_ROW_Y + 5;
        gfx.drawString(font, Component.literal("Selector id"), SIDE_PAD, rowY, 0xFFE0E0E0, false);
        rowY += ROW_H;
        gfx.drawString(font, Component.literal("Item tag"), SIDE_PAD, rowY, 0xFFE0E0E0, false);
        rowY += ROW_H;
        gfx.drawString(font, Component.literal("Plain chain"), SIDE_PAD, rowY, 0xFFE0E0E0, false);
        rowY += ROW_H;
        gfx.drawString(font, Component.literal("Enchanted chain"), SIDE_PAD, rowY, 0xFFE0E0E0, false);

        if (!errorMessage.isEmpty()) {
            int errY = FIRST_ROW_Y + ROW_H * 4 + 32;
            gfx.drawCenteredString(font, Component.literal(errorMessage),
                width / 2, errY, 0xFFFF6060);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activePicker != null) {
            activePicker.mouseClicked(mouseX, mouseY, button);
            return true;
        }
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void submit() {
        errorMessage = "";

        ResourceLocation id = parseId(idField.getValue(), "adventureitemnames");
        if (id == null) {
            errorMessage = "Selector id is invalid";
            return;
        }
        if (NameRegistry.shippedSelectors().containsKey(id)) {
            errorMessage = "A shipped selector with that id already exists";
            return;
        }

        ResourceLocation tag = parseId(tagField.getValue(), "minecraft");
        if (tag == null) {
            errorMessage = "Item tag is invalid";
            return;
        }

        if (plainChain.isEmpty()) {
            errorMessage = "Pick a plain chain";
            return;
        }
        if (enchantedChain.isEmpty()) {
            errorMessage = "Pick an enchanted chain";
            return;
        }

        Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
        tiers.put(NameTier.PLAIN.key(), plainChain.get());
        tiers.put(NameTier.ENCHANTED.key(), enchantedChain.get());
        NameSelector sel = new NameSelector(id, tag, tiers);
        buffer.addCustomSelector(sel);

        if (onDone != null) onDone.run();
        Minecraft.getInstance().setScreen(parent);
    }

    /** Parse "ns:path" or default the namespace. Returns null on failure. */
    private static ResourceLocation parseId(String raw, String defaultNamespace) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.contains(":")) {
            return ResourceLocation.tryBuild(defaultNamespace, trimmed);
        }
        return ResourceLocation.tryParse(trimmed);
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
