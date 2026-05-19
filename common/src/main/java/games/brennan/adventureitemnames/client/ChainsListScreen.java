package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.NameChain;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v3 — top-level Chains screen. One row per registered
 * {@link NameChain}, sorted with first-party {@code adventureitemnames:*}
 * chains first and datapack chains after (alphabetical within each group).
 * Each row exposes: chain id, pack id, segment count, enable/disable
 * checkbox, and an Open button that descends into
 * {@link ChainEditorScreen}.
 *
 * <p>Reuses v1/v2's {@link EditBuffer} for pending edits and the gated
 * {@link PreviewPanel} for live preview. {@code Save to pack} flushes
 * everything via {@link ConfigSave#commit}.
 */
@Environment(EnvType.CLIENT)
public final class ChainsListScreen extends Screen {

    private static final int LIST_TOP = 32;
    private static final int ENTRY_H = 22;
    private static final int ROW_PAD = 4;
    private static final int CHECKBOX_SIZE = 18;
    private static final int OPEN_W = 50;

    private final Screen parent;
    private final EditBuffer buffer;
    private ChainList list;
    private PreviewPanel preview;
    private Button saveButton;

    public ChainsListScreen(Screen parent, EditBuffer buffer) {
        super(Component.translatable("screen.adventureitemnames.chains.title"));
        this.parent = parent;
        this.buffer = buffer;
    }

    @Override
    protected void init() {
        List<NameChain> chains = orderedChains();
        int listBottom = height - PreviewPanel.HEIGHT - 32;
        list = new ChainList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, chains, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.HEIGHT - 26, 80, 20).build());

        saveButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.save"),
            b -> save()
        ).bounds(width - 88, height - PreviewPanel.HEIGHT - 26, 80, 20).build();
        saveButton.active = buffer.isDirty();
        addRenderableWidget(saveButton);

        preview = new PreviewPanel(buffer, null, true);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        super.render(gfx, mouseX, mouseY, partial);
        gfx.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
        if (saveButton != null) saveButton.active = buffer.isDirty();
        preview.render(gfx, mouseX, mouseY, partial);
    }

    private void save() {
        ConfigSave.commit(buffer, () -> {
            if (saveButton != null) saveButton.active = false;
            if (preview != null) preview.rerollNow();
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (preview != null && preview.keyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    EditBuffer buffer() { return buffer; }

    void rerollPreview() { if (preview != null) preview.rerollNow(); }

    /** First-party chains first, then datapack chains, both alphabetical. */
    private static List<NameChain> orderedChains() {
        Map<ResourceLocation, NameChain> all = NameRegistry.allChains();
        List<NameChain> first = new ArrayList<>();
        List<NameChain> rest = new ArrayList<>();
        for (Map.Entry<ResourceLocation, NameChain> e : all.entrySet()) {
            if ("adventureitemnames".equals(e.getKey().getNamespace())) first.add(e.getValue());
            else rest.add(e.getValue());
        }
        first.sort(Comparator.comparing(c -> c.id().toString()));
        rest.sort(Comparator.comparing(c -> c.id().toString()));
        first.addAll(rest);
        return first;
    }

    static final class ChainList extends ContainerObjectSelectionList<ChainList.RowEntry> {

        ChainList(Minecraft mc, int width, int height, int top,
                  List<NameChain> chains, ChainsListScreen host) {
            super(mc, width, height, top, ENTRY_H);
            for (NameChain c : chains) addEntry(new RowEntry(c, host));
        }

        @Override public int getRowWidth() { return width - 16; }
        @Override protected int getScrollbarPosition() { return width - 6; }

        static final class RowEntry extends ContainerObjectSelectionList.Entry<RowEntry> {

            private final NameChain chain;
            private final ChainsListScreen host;
            private final Checkbox enabledBox;
            private final Button openButton;

            RowEntry(NameChain chain, ChainsListScreen host) {
                this.chain = chain;
                this.host = host;

                boolean enabledNow = NamingConfig.isChainEnabled(chain.id());
                this.enabledBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(enabledNow)
                    .onValueChange((c, v) -> {
                        // No EditBuffer slot for chain enable yet — for v3 this row is read-only display.
                        // Reset the checkbox so the visual state matches truth.
                        c.onPress(); // toggle back
                    })
                    .build();
                this.enabledBox.active = false;

                this.openButton = Button.builder(
                    Component.translatable("screen.adventureitemnames.action.open"),
                    b -> Minecraft.getInstance().setScreen(new ChainEditorScreen(host, host.buffer(), chain))
                ).bounds(0, 0, OPEN_W, 18).build();
            }

            @Override public List<? extends NarratableEntry> narratables() { return List.of(enabledBox, openButton); }
            @Override public List<? extends GuiEventListener> children() { return List.of(enabledBox, openButton); }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int x = rowLeft + ROW_PAD;
                int y = rowTop + (rowHeight - 9) / 2;

                String packId = NameRegistry.packIdOfChain(chain.id());
                int packW = Math.min(110, Minecraft.getInstance().font.width(packId));
                int idAvail = rowWidth - ROW_PAD * 2 - OPEN_W - CHECKBOX_SIZE - packW - 60;
                String idText = chain.id().toString();
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(idText), x, y, 0xFFE8E8E8, false);
                x += idAvail;

                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(packId), x, y, 0xFF909090, false);
                x += packW + 8;

                String segCount = chain.segments().size() + " seg";
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(segCount), x, y, 0xFFC0C0C0, false);
                x += 40;

                enabledBox.setX(x);
                enabledBox.setY(rowTop + (rowHeight - CHECKBOX_SIZE) / 2);
                enabledBox.render(gfx, mouseX, mouseY, partial);
                x += CHECKBOX_SIZE + 6;

                openButton.setX(rowLeft + rowWidth - OPEN_W - ROW_PAD);
                openButton.setY(rowTop + (rowHeight - 18) / 2);
                openButton.render(gfx, mouseX, mouseY, partial);
            }
        }
    }
}
