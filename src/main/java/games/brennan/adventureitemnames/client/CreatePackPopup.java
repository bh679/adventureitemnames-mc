package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.internal.PackCreator;
import games.brennan.adventureitemnames.internal.PackPaths;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Popup for scaffolding a brand-new datapack from inside the in-game
 * editor. Two inputs:
 * <ul>
 *   <li><b>Name</b> — friendly display name. Slugified
 *       ({@link AddChainPopup#slugify}) to derive the on-disk folder name
 *       and the starter pool id.</li>
 *   <li><b>Description</b> — written verbatim into the new pack's
 *       {@code pack.mcmeta} description field.</li>
 * </ul>
 *
 * <p>Submit calls {@link PackCreator#create} which writes
 * {@code <world>/datapacks/<slug>/} (and mirrors to the source tree when
 * {@link PackPaths#projectRootAvailable()} is true), then
 * {@link PackReload#enableAndReload} which discovers the new pack,
 * activates it on the integrated server, and re-runs the mod's reload
 * listeners. Once the async reload resolves, the popup switches to
 * {@link PoolListScreen} for the new pack so the user can immediately
 * edit the starter pool.
 */
@Environment(EnvType.CLIENT)
public final class CreatePackPopup extends Screen {

    private static final int FORM_W = 320;
    private static final int LABEL_W = 90;
    private static final int FIELD_H = 20;
    private static final int GAP = 6;

    private final Screen parent;
    private final EditBuffer buffer;

    private EditBox nameBox;
    private EditBox descriptionBox;
    private Button createButton;
    private String statusText;
    private boolean inFlight;

    public CreatePackPopup(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.datapacks.new_pack.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int formX = cx - FORM_W / 2;
        int rowH = FIELD_H + GAP;
        int y = height / 2 - (rowH * 2 + 30) / 2;

        nameBox = new EditBox(font, formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H, Component.literal("name"));
        nameBox.setMaxLength(60);
        nameBox.setHint(Component.literal("New pack"));
        nameBox.setResponder(s -> refreshCreateActive());
        addRenderableWidget(nameBox);

        y += rowH;
        descriptionBox = new EditBox(font, formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H,
            Component.literal("description"));
        descriptionBox.setMaxLength(120);
        descriptionBox.setHint(Component.literal("A short summary"));
        descriptionBox.setResponder(s -> refreshCreateActive());
        addRenderableWidget(descriptionBox);

        int footerY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(cx - 110, footerY, 100, 20).build());

        createButton = Button.builder(Component.literal("Create"), b -> submit())
            .bounds(cx + 10, footerY, 100, 20).build();
        createButton.active = false;
        addRenderableWidget(createButton);
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
        drawLabel(gfx, "Name", formX, y);          y += rowH;
        drawLabel(gfx, "Description", formX, y);

        int previewY = formTop + rowH * 2 + 6;
        String slug = currentSlug();
        if (slug.isEmpty()) {
            gfx.drawString(font, "Saves as: (enter a name)", formX + LABEL_W, previewY, 0xFFA0A0A0, false);
        } else {
            gfx.drawString(font, "Saves as: <world>/datapacks/" + slug + "/",
                formX + LABEL_W, previewY, 0xFFA0A0A0, false);

            Path src = PackPaths.srcTreePackRoot(slug);
            if (src != null) {
                gfx.drawString(font,
                    "+ source tree copy: common/src/main/resources/resourcepacks/" + slug + "/",
                    formX + LABEL_W, previewY + 11, 0xFFA0A0A0, false);
            }

            if (collisionExists(slug)) {
                gfx.drawString(font,
                    Component.translatable("screen.adventureitemnames.datapacks.new_pack.collision"),
                    formX + LABEL_W, previewY + 24, 0xFFFFAA55, false);
            }
        }

        if (statusText != null) {
            gfx.drawCenteredString(font, Component.literal(statusText),
                width / 2, height - 50, 0xFFFFCC66);
        }

        refreshCreateActive();
    }

    private void drawLabel(GuiGraphics gfx, String text, int x, int y) {
        gfx.drawString(font, Component.literal(text), x, y, 0xFFE0E0E0, false);
    }

    private String currentSlug() {
        if (nameBox == null) return "";
        return AddChainPopup.slugify(nameBox.getValue() == null ? "" : nameBox.getValue().trim());
    }

    private boolean collisionExists(String slug) {
        Path src = PackPaths.srcTreePackRoot(slug);
        if (src != null && Files.exists(src)) return true;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return false;
        Path worldPack = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
            .resolve(slug);
        return Files.exists(worldPack);
    }

    private void refreshCreateActive() {
        if (createButton == null) return;
        if (inFlight) { createButton.active = false; return; }
        String slug = currentSlug();
        boolean nameOk = !slug.isEmpty();
        boolean descOk = descriptionBox != null && !descriptionBox.getValue().trim().isEmpty();
        createButton.active = nameOk && descOk && !collisionExists(slug);
    }

    private void submit() {
        if (inFlight) return;
        String slug = currentSlug();
        String description = descriptionBox.getValue().trim();
        if (slug.isEmpty() || description.isEmpty()) return;

        inFlight = true;
        statusText = "Creating pack…";
        createButton.active = false;

        PackCreator.CreateResult result = PackCreator.create(slug, description);
        if (!result.ok()) {
            statusText = "Failed: " + result.error();
            inFlight = false;
            refreshCreateActive();
            return;
        }

        PackReload.enableAndReload(result.packId(), () -> openNewPack(result.packId()));
    }

    private void openNewPack(String packId) {
        var snapshot = PackGrouping.snapshot();
        PackGrouping.PackView pv = snapshot.get(packId);
        if (pv == null) {
            statusText = "Pack created but not yet visible — returning to list";
            inFlight = false;
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        Minecraft.getInstance().setScreen(new PoolListScreen(parent, buffer, pv));
    }

    @Override
    public void onClose() {
        if (inFlight) return;
        Minecraft.getInstance().setScreen(parent);
    }
}
