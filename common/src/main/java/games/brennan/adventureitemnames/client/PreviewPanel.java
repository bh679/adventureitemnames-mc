package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Bottom-docked preview strip used on every config screen. Holds the
 * last batch of 10 rolled names plus a 🎲 re-roll button. Stateless
 * across screen navigations — caller passes the {@link EditBuffer} so
 * pending edits show through.
 *
 * <p>This widget is rendered + interactive but is not a child of a
 * vanilla list/layout — the host screen positions it at a fixed
 * {@code height - h} y offset and rebuilds it on {@link #rebuild}.
 */
@Environment(EnvType.CLIENT)
public final class PreviewPanel {

    public static final int HEIGHT = 64;
    private static final int PADDING_X = 8;
    private static final int LINE_HEIGHT = 9;

    private final EditBuffer buffer;
    private final ResourceLocation forcePool;
    private List<String> names = List.of();
    private Button reroll;
    private int screenWidth;
    private int screenHeight;

    public PreviewPanel(EditBuffer buffer, ResourceLocation forcePool) {
        this.buffer = buffer;
        this.forcePool = forcePool;
    }

    /** Re-position the embedded re-roll button and roll an initial batch. */
    public void rebuild(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        rerollNow();
        reroll = Button.builder(Component.literal("🎲"), b -> rerollNow())
            .bounds(screenWidth - 32 - PADDING_X, screenHeight - HEIGHT + 4, 32, 16)
            .build();
    }

    public Button button() { return reroll; }

    public void rerollNow() {
        names = PreviewRoller.roll(10, buffer, forcePool);
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        if (screenWidth == 0) return;
        int y0 = screenHeight - HEIGHT;
        gfx.fill(0, y0, screenWidth, screenHeight, 0xCC101010);
        gfx.fill(0, y0, screenWidth, y0 + 1, 0xFF606060);

        gfx.drawString(Minecraft.getInstance().font,
            Component.literal("Preview · 10 rolled names"),
            PADDING_X, y0 + 4, 0xFFA0A0A0, false);

        int lineY = y0 + 16;
        int colWidth = (screenWidth - PADDING_X * 2) / 2;
        for (int i = 0; i < names.size() && i < 10; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = PADDING_X + col * colWidth;
            int y = lineY + row * LINE_HEIGHT;
            String prefix = (i + 1) + ". ";
            String label = prefix + names.get(i);
            List<FormattedCharSequence> trimmed = Minecraft.getInstance().font.split(
                Component.literal(label), colWidth - 8);
            if (!trimmed.isEmpty()) {
                gfx.drawString(Minecraft.getInstance().font, trimmed.get(0), x, y, 0xFFE0E0E0, false);
            }
        }
    }
}
