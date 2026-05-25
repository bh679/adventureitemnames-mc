package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.internal.PoolCreator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Popup for adding a new pool to a specific pack from inside the pool
 * editor. Single input — friendly pool name slugified to derive both the
 * file name and the pool's {@link ResourceLocation} path. The pool ships
 * with one placeholder entry so it's immediately editable via the
 * existing entry editor.
 *
 * <p>On submit: {@link PoolCreator#create} writes the pool file (world
 * datapacks dir for {@code file/<slug>} packs, source tree for built-in
 * packs in dev mode), then {@link PackReload#enableAndReload} re-runs the
 * reload listeners so {@link PackGrouping#snapshot()} picks up the new
 * pool. After reload, navigates back to a freshly-rebuilt
 * {@link PoolListScreen} for the same pack with the new pool visible.
 */
@Environment(EnvType.CLIENT)
public final class CreatePoolPopup extends Screen {

    private static final int FORM_W = 320;
    private static final int LABEL_W = 70;
    private static final int FIELD_H = 20;
    private static final int GAP = 6;
    private static final String NAMESPACE = "adventureitemnames";

    private final PoolListScreen parent;
    private final EditBuffer buffer;
    private final PackGrouping.PackView pack;

    private EditBox nameBox;
    private Button createButton;
    private String statusText;
    private boolean inFlight;

    public CreatePoolPopup(PoolListScreen parent, EditBuffer buffer, PackGrouping.PackView pack) {
        super(Component.translatable("screen.adventureitemnames.pools.new_pool.title"));
        this.parent = parent;
        this.buffer = buffer;
        this.pack = pack;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int formX = cx - FORM_W / 2;
        int y = height / 2 - 30;

        nameBox = new EditBox(font, formX + LABEL_W, y, FORM_W - LABEL_W, FIELD_H,
            Component.literal("name"));
        nameBox.setMaxLength(60);
        nameBox.setHint(Component.literal("e.g. Adjective"));
        nameBox.setResponder(s -> refreshCreateActive());
        addRenderableWidget(nameBox);

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
        int y = height / 2 - 30 + 6;
        gfx.drawString(font, Component.literal("Name"), formX, y, 0xFFE0E0E0, false);

        int previewY = height / 2 - 30 + FIELD_H + GAP + 8;
        String slug = currentSlug();
        if (slug.isEmpty()) {
            gfx.drawString(font, "Saves as: (enter a name)",
                formX + LABEL_W, previewY, 0xFFA0A0A0, false);
        } else {
            gfx.drawString(font,
                "Saves as: " + PackGrouping.friendlyPackName(pack.packId()) + " / " + NAMESPACE + ":" + slug,
                formX + LABEL_W, previewY, 0xFFA0A0A0, false);

            if (collisionExists(slug)) {
                gfx.drawString(font,
                    Component.translatable("screen.adventureitemnames.pools.new_pool.collision"),
                    formX + LABEL_W, previewY + 11, 0xFFFFAA55, false);
            }
        }

        if (statusText != null) {
            gfx.drawCenteredString(font, Component.literal(statusText),
                width / 2, height - 50, 0xFFFFCC66);
        }

        refreshCreateActive();
    }

    private String currentSlug() {
        if (nameBox == null) return "";
        return AddChainPopup.slugify(nameBox.getValue() == null ? "" : nameBox.getValue().trim());
    }

    private boolean collisionExists(String slug) {
        ResourceLocation id = ResourceLocation.tryParse(NAMESPACE + ":" + slug);
        if (id == null) return false;
        return pack.pools().stream().anyMatch(pv -> pv.poolId().equals(id));
    }

    private void refreshCreateActive() {
        if (createButton == null) return;
        if (inFlight) { createButton.active = false; return; }
        String slug = currentSlug();
        createButton.active = !slug.isEmpty() && !collisionExists(slug);
    }

    private void submit() {
        if (inFlight) return;
        String slug = currentSlug();
        if (slug.isEmpty()) return;

        inFlight = true;
        statusText = "Creating pool…";
        createButton.active = false;

        PoolCreator.CreateResult result = PoolCreator.create(pack.packId(), slug, null);
        if (!result.ok()) {
            statusText = "Failed: " + result.error();
            inFlight = false;
            refreshCreateActive();
            return;
        }

        PackReload.enableAndReload(pack.packId(), this::reopenWithNewPool);
    }

    private void reopenWithNewPool() {
        PackGrouping.PackView refreshed = PackGrouping.snapshot().get(pack.packId());
        if (refreshed == null) {
            statusText = "Pool created but pack snapshot missing — returning to list";
            inFlight = false;
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        Minecraft.getInstance().setScreen(new PoolListScreen(parent.parentScreen(), buffer, refreshed));
    }

    @Override
    public void onClose() {
        if (inFlight) return;
        Minecraft.getInstance().setScreen(parent);
    }
}
