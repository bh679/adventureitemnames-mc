package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Hub screen — top of the config menu hierarchy. Three buttons:
 * <strong>Datapacks</strong> (v1), <strong>Spawn Chances</strong> (v2
 * placeholder), <strong>Chains</strong> (v3 placeholder). Holds the
 * shared {@link EditBuffer} so navigating into a sub-screen and back
 * preserves pending edits.
 *
 * <p>{@code Save to pack} lives on the sub-screens, not here — the hub
 * has no rows to edit. Pending edits across sub-screens stay in the
 * buffer until the user saves on a sub-screen, or until this hub's
 * {@code onClose} runs — at which point an
 * {@link UnsavedChangesPrompt} catches a dirty buffer and offers
 * Save / Discard / Cancel before the user actually exits the config UI.
 * Sub-screens deliberately do NOT trigger the prompt on their own
 * {@code onClose} so navigating between config screens never asks "do
 * you want to save" about edits made on a different screen.
 */
@Environment(EnvType.CLIENT)
public final class ConfigScreen extends Screen {

    private final Screen parent;
    private final EditBuffer buffer;
    private ConfirmDialog activeConfirm;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("screen.adventureitemnames.config.title"));
        this.parent = parent;
        this.buffer = new EditBuffer();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;
        int btnW = 200, btnH = 20, gap = 4;

        addRenderableWidget(Button.builder(
            Component.translatable("screen.adventureitemnames.config.datapacks"),
            b -> Minecraft.getInstance().setScreen(new DatapacksListScreen(this, buffer))
        ).bounds(cx - btnW / 2, cy - btnH - gap - btnH - gap, btnW, btnH).build());

        addRenderableWidget(Button.builder(
            Component.translatable("screen.adventureitemnames.config.spawn_chances"),
            b -> Minecraft.getInstance().setScreen(new SpawnChancesScreen(this, buffer))
        ).bounds(cx - btnW / 2, cy - btnH / 2, btnW, btnH).build());

        addRenderableWidget(Button.builder(
            Component.translatable("screen.adventureitemnames.config.chains"),
            b -> Minecraft.getInstance().setScreen(new ChainsListScreen(this, buffer))
        ).bounds(cx - btnW / 2, cy + btnH / 2 + gap, btnW, btnH).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.done"),
            b -> onClose()
        ).bounds(cx - btnW / 2, height - 28, btnW, btnH).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (activeConfirm != null) {
            super.renderBackground(gfx, mouseX, mouseY, partial);
            activeConfirm.render(gfx, mouseX, mouseY);
            return;
        }
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeConfirm != null) { activeConfirm.mouseClicked(mouseX, mouseY, button); return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeConfirm != null && activeConfirm.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (!buffer.isDirty()) {
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        UnsavedChangesPrompt.forClose(width, height, buffer,
            () -> Minecraft.getInstance().setScreen(parent),
            d -> activeConfirm = d,
            () -> activeConfirm = null);
    }
}
