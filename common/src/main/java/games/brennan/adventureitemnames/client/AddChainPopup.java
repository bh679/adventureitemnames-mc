package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NameSegment;
import games.brennan.adventureitemnames.internal.NameRegistry;
import games.brennan.adventureitemnames.internal.PackPaths;
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
import java.util.List;

/**
 * Popup for creating a brand-new chain via the in-game datapack editor.
 * Two inputs:
 * <ul>
 *   <li><b>Name</b> — friendly display name (e.g. {@code "Mob Adjective"}).
 *       Used as the chain's seed label and lowered to derive the chain id
 *       (spaces → underscores, non-alphanum stripped). Live preview of
 *       the derived id sits below the form.</li>
 *   <li><b>Pack</b> — cycling button across
 *       {@link PackPaths#knownWritablePackIds()}, defaults to the base
 *       mod pack. Determines where the new chain's metadata file lands
 *       on save.</li>
 * </ul>
 *
 * <p>Submit registers the new chain in the {@link NameRegistry} overlay
 * (one empty seed segment with the friendly name as its label), tracks
 * the pack choice in the {@link EditBuffer} so {@link ConfigSave} writes
 * it to the right pack, and opens {@link ChainEditorScreen} so the user
 * can immediately add refs.
 */
@Environment(EnvType.CLIENT)
public final class AddChainPopup extends Screen {

    private static final String NAMESPACE = "adventureitemnames";
    private static final int FORM_W = 300;
    private static final int LABEL_W = 70;
    private static final int FIELD_H = 20;
    private static final int GAP = 6;

    private final Screen parent;
    private final EditBuffer buffer;
    private final List<String> packCycle;

    private EditBox nameBox;
    private Button packButton;
    private Button saveButton;

    private int packIndex;

    public AddChainPopup(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.chains.new_chain.title"));
        this.parent = parent;
        this.buffer = buffer;
        this.packCycle = new ArrayList<>(PackPaths.knownWritablePackIds());
        if (packCycle.isEmpty()) {
            packCycle.add("mod/adventureitemnames");
        }
        this.packIndex = 0;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int formX = cx - FORM_W / 2;
        int rowH = FIELD_H + GAP;
        int y = height / 2 - (rowH * 2 + 30) / 2;

        // Row 1: friendly name
        nameBox = new EditBox(font, formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H, Component.literal("name"));
        nameBox.setMaxLength(60);
        nameBox.setHint(Component.literal("New chain"));
        nameBox.setResponder(s -> refreshSaveActive());
        addRenderableWidget(nameBox);

        y += rowH;
        // Row 2: target pack (cycling button)
        packButton = Button.builder(packLabel(), b -> cyclePack())
            .bounds(formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H).build();
        addRenderableWidget(packButton);

        // Footer
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
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);

        int cx = width / 2;
        int formX = cx - FORM_W / 2;
        int rowH = FIELD_H + GAP;
        int formTop = height / 2 - (rowH * 2 + 30) / 2;
        int y = formTop + 6;
        drawLabel(gfx, "Name", formX, y);            y += rowH;
        drawLabel(gfx, "Pack", formX, y);

        // Derived-ID preview + collision warning below the form.
        ResourceLocation parsedId = parseId();
        int previewY = formTop + rowH * 2 + 6;
        String previewText = parsedId != null
            ? "Saves as: " + parsedId
            : "Saves as: (enter a name)";
        gfx.drawString(font, Component.literal(previewText),
            formX + LABEL_W, previewY, 0xFFA0A0A0, false);

        if (parsedId != null && NameRegistry.allChains().containsKey(parsedId)) {
            gfx.drawString(font,
                Component.translatable("screen.adventureitemnames.chains.new_chain.collision"),
                formX + LABEL_W, previewY + 11, 0xFFFFAA55, false);
        }

        refreshSaveActive();
    }

    private void drawLabel(GuiGraphics gfx, String text, int x, int y) {
        gfx.drawString(font, Component.literal(text), x, y, 0xFFE0E0E0, false);
    }

    private void cyclePack() {
        packIndex = (packIndex + 1) % packCycle.size();
        packButton.setMessage(packLabel());
    }

    private Component packLabel() {
        return Component.literal(PackGrouping.friendlyPackName(packCycle.get(packIndex)));
    }

    private void submit() {
        ResourceLocation id = parseId();
        if (id == null) return;
        if (NameRegistry.allChains().containsKey(id)) return;

        String packId = packCycle.get(packIndex);
        String label = friendlyName();
        NameSegment seed = new NameSegment(List.of(), 1.0f, " ", false, label);
        NameChain fresh = new NameChain(id, List.of(seed), true);

        NameRegistry.putChainInMemory(fresh);
        buffer.addNewChain(id, packId);

        Minecraft.getInstance().setScreen(new ChainEditorScreen(parent, buffer, fresh));
    }

    private void refreshSaveActive() {
        if (saveButton == null) return;
        ResourceLocation id = parseId();
        saveButton.active = id != null && !NameRegistry.allChains().containsKey(id);
    }

    /**
     * Derive the chain id from the Name field: lowercase, spaces and runs
     * of non-alphanumerics collapsed into a single underscore, leading /
     * trailing underscores trimmed. Returns {@code null} when the field
     * is empty or the derivation produces an empty path.
     */
    private ResourceLocation parseId() {
        String raw = friendlyName();
        if (raw.isEmpty()) return null;
        String path = slugify(raw);
        if (path.isEmpty()) return null;
        return ResourceLocation.tryParse(NAMESPACE + ":" + path);
    }

    private String friendlyName() {
        if (nameBox == null) return "";
        String text = nameBox.getValue();
        return text == null ? "" : text.trim();
    }

    static String slugify(String input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastUnderscore = false;
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) (c - 'A' + 'a'));
                lastUnderscore = false;
            } else {
                if (!lastUnderscore && out.length() > 0) {
                    out.append('_');
                    lastUnderscore = true;
                }
            }
        }
        // Trim trailing underscore left by the final run.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
