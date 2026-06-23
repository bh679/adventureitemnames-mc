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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Form screen for creating a new user-defined selector. Fields:
 * <ul>
 *   <li><b>Selector id</b> — auto-derived from the picked tag's path
 *       ({@code minecraft:maces} → {@code adventureitemnames:mace}) but
 *       editable before save. Validated as a {@link ResourceLocation};
 *       collision against an existing selector id surfaces a warning row.</li>
 *   <li><b>Item tag</b> — opens {@link TagPicker} on click. Required.</li>
 *   <li><b>Plain chain</b> — opens {@link ChainPicker}. Required.</li>
 *   <li><b>Enchanted chain</b> — opens {@link ChainPicker}. Optional;
 *       defaults to plain when left blank.</li>
 * </ul>
 *
 * <p>Save → {@code buffer.addCustomSelector(...)} + return to parent.
 * Cancel → return to parent without changes. The new selector becomes
 * visible immediately in the parent Selectors screen via the EditBuffer.
 */
@Environment(EnvType.CLIENT)
public final class AddSelectorPopup extends Screen {

    private static final int FORM_W = 280;
    private static final int LABEL_W = 90;
    private static final int FIELD_H = 20;
    private static final int GAP = 6;

    private final Screen parent;
    private final EditBuffer buffer;
    private final List<Optional<ResourceLocation>> chainCycle;

    private EditBox idBox;
    private Button tagButton;
    private Button plainButton;
    private Button enchantedButton;
    private Button saveButton;

    private ResourceLocation pickedTag;
    private Optional<ResourceLocation> plainChain = Optional.empty();
    private Optional<ResourceLocation> enchantedChain = Optional.empty();
    private boolean idEditedManually = false;

    private TagPicker activeTagPicker;
    private ChainPicker activeChainPicker;

    public AddSelectorPopup(Screen parent, EditBuffer buffer) {
        super(Component.literal("Add custom selector"));
        this.parent = parent;
        this.buffer = buffer;
        this.chainCycle = buildChainCycle();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int formX = cx - FORM_W / 2;
        int rowH = FIELD_H + GAP;
        int y = height / 2 - (rowH * 4 + 30) / 2;

        // Row 1: selector id (text-box, auto-derived but editable)
        idBox = new EditBox(font, formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H, Component.literal("id"));
        idBox.setMaxLength(80);
        idBox.setHint(Component.literal("adventureitemnames:..."));
        idBox.setResponder(s -> {
            if (idBox.isFocused()) idEditedManually = true;
            refreshSaveActive();
        });
        addRenderableWidget(idBox);

        y += rowH;
        // Row 2: item tag
        tagButton = Button.builder(Component.literal("Pick tag…"), b -> openTagPicker())
            .bounds(formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H).build();
        addRenderableWidget(tagButton);

        y += rowH;
        // Row 3: plain chain
        plainButton = Button.builder(Component.literal(formatChain(plainChain)),
            b -> openChainPicker(true))
            .bounds(formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H).build();
        addRenderableWidget(plainButton);

        y += rowH;
        // Row 4: enchanted chain
        enchantedButton = Button.builder(Component.literal(formatChain(enchantedChain) + "  (defaults to plain)"),
            b -> openChainPicker(false))
            .bounds(formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H).build();
        addRenderableWidget(enchantedButton);

        // Footer: cancel + save
        int footerY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
            b -> onClose())
            .bounds(cx - 110, footerY, 100, 20).build());

        saveButton = Button.builder(Component.literal("Add"), b -> submit())
            .bounds(cx + 10, footerY, 100, 20).build();
        saveButton.active = false;
        addRenderableWidget(saveButton);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (activeTagPicker != null) {
            GuiCompat.renderBackground(this, gfx, mouseX, mouseY, partial);
            activeTagPicker.render(gfx, mouseX, mouseY);
            return;
        }
        if (activeChainPicker != null) {
            GuiCompat.renderBackground(this, gfx, mouseX, mouseY, partial);
            activeChainPicker.render(gfx, mouseX, mouseY);
            return;
        }
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);

        int cx = width / 2;
        int formX = cx - FORM_W / 2;
        int rowH = FIELD_H + GAP;
        int y = height / 2 - (rowH * 4 + 30) / 2 + 6;
        drawLabel(gfx, "Id",        formX, y);                       y += rowH;
        drawLabel(gfx, "Item tag",  formX, y);                       y += rowH;
        drawLabel(gfx, "Plain",     formX, y);                       y += rowH;
        drawLabel(gfx, "Enchanted", formX, y);

        // Collision warning under the id box
        ResourceLocation parsedId = parseId();
        if (parsedId != null && NameRegistry.allSelectors().containsKey(parsedId)
            && !NameRegistry.isUserSelector(parsedId)) {
            int wy = height / 2 - (rowH * 4 + 30) / 2 + FIELD_H + 1;
            gfx.drawString(font,
                Component.literal("⚠ id collides with shipped selector — datapack wins"),
                formX + LABEL_W, wy, 0xFFFFAA55, false);
        }

        refreshSaveActive();
    }

    private void drawLabel(GuiGraphics gfx, String text, int x, int y) {
        gfx.drawString(font, Component.literal(text), x, y, 0xFFE0E0E0, false);
    }

    private void openTagPicker() {
        List<ResourceLocation> tags = collectItemTagIds();
        activeTagPicker = new TagPicker(width, height, "Pick item tag", tags,
            new TagPicker.Listener() {
                @Override public void onPicked(ResourceLocation tag) {
                    activeTagPicker = null;
                    pickedTag = tag;
                    tagButton.setMessage(Component.literal("#" + tag.toString()));
                    if (!idEditedManually) {
                        idBox.setValue(deriveIdFromTag(tag));
                    }
                    refreshSaveActive();
                }
                @Override public void onCancelled() {
                    activeTagPicker = null;
                }
            });
    }

    private void openChainPicker(boolean plain) {
        Optional<ResourceLocation> current = plain ? plainChain : enchantedChain;
        activeChainPicker = new ChainPicker(width, height,
            plain ? "Plain chain" : "Enchanted chain",
            chainCycle, current,
            new ChainPicker.Listener() {
                @Override public void onPicked(Optional<ResourceLocation> chain) {
                    activeChainPicker = null;
                    if (plain) {
                        plainChain = chain;
                        plainButton.setMessage(Component.literal(formatChain(plainChain)));
                    } else {
                        enchantedChain = chain;
                        String suffix = chain.isEmpty() ? "  (defaults to plain)" : "";
                        enchantedButton.setMessage(Component.literal(formatChain(enchantedChain) + suffix));
                    }
                    refreshSaveActive();
                }
                @Override public void onCancelled() {
                    activeChainPicker = null;
                }
            });
    }

    private void submit() {
        ResourceLocation id = parseId();
        if (id == null || pickedTag == null || plainChain.isEmpty()) return;
        Map<String, ResourceLocation> tiers = new LinkedHashMap<>();
        tiers.put(NameTier.PLAIN.key(), plainChain.get());
        // Enchanted defaults to plain when unset.
        ResourceLocation enchantedId = enchantedChain.orElse(plainChain.get());
        tiers.put(NameTier.ENCHANTED.key(), enchantedId);
        buffer.addCustomSelector(new NameSelector(id, pickedTag, tiers));
        Minecraft.getInstance().setScreen(parent);
    }

    private void refreshSaveActive() {
        if (saveButton == null) return;
        ResourceLocation id = parseId();
        saveButton.active = id != null && pickedTag != null && plainChain.isPresent();
    }

    private ResourceLocation parseId() {
        if (idBox == null) return null;
        String text = idBox.getValue();
        if (text == null || text.isBlank()) return null;
        return ResourceLocation.tryParse(text.trim());
    }

    /** {@code minecraft:maces} → {@code adventureitemnames:mace}; strips trailing 's' on plural paths. */
    static String deriveIdFromTag(ResourceLocation tag) {
        String path = tag.getPath();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        if (path.endsWith("s") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return "adventureitemnames:" + path;
    }

    private static String formatChain(Optional<ResourceLocation> chain) {
        if (chain.isEmpty()) return "Pick chain…";
        return SelectorsScreen.formatChainLabelFull(chain);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeTagPicker != null) { activeTagPicker.mouseClicked(mouseX, mouseY, button); return true; }
        if (activeChainPicker != null) { activeChainPicker.mouseClicked(mouseX, mouseY, button); return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // MC 1.21 added a horizontal-scroll (scrollX) parameter to mouseScrolled.
    @Override
    //? if >=1.21.1 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTagPicker != null && activeTagPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (activeChainPicker != null && activeChainPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (activeTagPicker != null && activeTagPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        if (activeChainPicker != null && activeChainPicker.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }*///?}

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeTagPicker != null && activeTagPicker.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (activeChainPicker != null && activeChainPicker.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (activeTagPicker != null && activeTagPicker.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Build the dropdown cycle list: (none) first, then all chains sorted by id. */
    private static List<Optional<ResourceLocation>> buildChainCycle() {
        List<ResourceLocation> sorted = new ArrayList<>(NameRegistry.allChains().keySet());
        sorted.sort(Comparator.comparing(ResourceLocation::toString));
        List<Optional<ResourceLocation>> out = new ArrayList<>(sorted.size() + 1);
        out.add(Optional.empty());
        for (ResourceLocation rl : sorted) out.add(Optional.of(rl));
        return List.copyOf(out);
    }

    /**
     * Collect every {@link TagKey<Item>} known to the client at open time
     * from {@link BuiltInRegistries#ITEM} (vanilla + datapack-registered).
     * Sorted alphabetically by id string.
     */
    static List<ResourceLocation> collectItemTagIds() {
        List<ResourceLocation> out = new ArrayList<>();
        BuiltInRegistries.ITEM.getTagNames().forEach(t -> out.add(t.location()));
        out.sort(Comparator.comparing(ResourceLocation::toString));
        return out;
    }
}
