package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Coming in v2 / v3" stub used for the two unbuilt hub buttons. Plain
 * back button and a centered body string. No editable state.
 */
@Environment(EnvType.CLIENT)
public final class PlaceholderScreen extends Screen {

    private final Screen parent;
    private final Component body;
    private MultiLineLabel wrappedBody = MultiLineLabel.EMPTY;

    public PlaceholderScreen(Screen parent, Component title, Component body) {
        super(title);
        this.parent = parent;
        this.body = body;
    }

    @Override
    protected void init() {
        wrappedBody = MultiLineLabel.create(font, body, Math.min(width - 80, 360));

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        int textY = height / 2 - wrappedBody.getLineCount() * 5;
        wrappedBody.renderCentered(gfx, width / 2, textY, 11, 0xFFC0C0C0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
