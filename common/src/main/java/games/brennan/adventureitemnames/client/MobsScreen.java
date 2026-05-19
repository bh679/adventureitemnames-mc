package games.brennan.adventureitemnames.client;

import games.brennan.adventureitemnames.api.MobCategory;
import games.brennan.adventureitemnames.api.NamingConfig;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-mob-category editor — opened from one of the
 * {@code MOB_PASSIVE} / {@code MOB_VILLAGER} rows on
 * {@link SpawnChancesScreen}.
 *
 * <p>Lists every vanilla entity type in the bucket returned by
 * {@link MobCategoryBuckets#entitiesIn(MobCategory)}. Each row shows a
 * spawn-egg icon, the mob's localized name, and an enable checkbox.
 * Toggling the checkbox stages an entry in
 * {@link EditBuffer#setEntityEnabled} which {@link ConfigSave} persists
 * into the user config's {@code mobs.entity_ids[]} block.
 */
@Environment(EnvType.CLIENT)
public final class MobsScreen extends Screen {

    private static final int ICON_SIZE     = 16;
    private static final int CHECKBOX_SIZE = 18;
    private static final int CELL_PAD      = 4;
    private static final int CELL_GAP      = 6;
    private static final int GAP_BETWEEN   = 6;
    private static final int LIST_TOP      = 32;
    private static final int ENTRY_H       = 22;

    private final Screen parent;
    private final EditBuffer buffer;
    private final MobCategory category;
    private MobList list;
    private PreviewPanel preview;
    private Button saveButton;

    public MobsScreen(Screen parent, EditBuffer buffer, MobCategory category) {
        super(titleFor(category));
        if (category == null) throw new IllegalArgumentException("category");
        this.parent = parent;
        this.buffer = buffer;
        this.category = category;
    }

    private static Component titleFor(MobCategory category) {
        return switch (category) {
            case PASSIVE -> Component.translatable("screen.adventureitemnames.mobs.passive.title");
            case VILLAGER -> Component.translatable("screen.adventureitemnames.mobs.villager.title");
        };
    }

    @Override
    protected void init() {
        List<EntityType<?>> entities = MobCategoryBuckets.entitiesIn(category);
        int listBottom = height - PreviewPanel.currentHeight() - 32;
        list = new MobList(minecraft, width, listBottom - LIST_TOP, LIST_TOP, entities, this);
        addRenderableWidget(list);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.back"),
            b -> onClose()
        ).bounds(8, height - PreviewPanel.currentHeight() - 26, 80, 20).build());

        saveButton = Button.builder(
            Component.translatable("screen.adventureitemnames.action.save"),
            b -> save()
        ).bounds(width - 88, height - PreviewPanel.currentHeight() - 26, 80, 20).build();
        saveButton.active = buffer.isDirty();
        addRenderableWidget(saveButton);

        if (preview == null) preview = new PreviewPanel(buffer, null, true, this::rebuildWidgets);
        preview.rebuild(width, height);
        addRenderableWidget(preview.button());
        addRenderableWidget(preview.toggleButton());
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

    /**
     * Best-effort spawn egg lookup for {@code type}. Returns the matching
     * spawn egg {@link ItemStack}, or a placeholder (an enchanted book —
     * easy to spot and never colliding with a real mob icon) when no
     * spawn egg is registered for that entity type.
     */
    static ItemStack iconForEntity(EntityType<?> type) {
        SpawnEggItem egg = SpawnEggItem.byId(type);
        if (egg != null) return new ItemStack(egg);
        Item fallback = BuiltInRegistries.ITEM.getOptional(
            ResourceLocation.fromNamespaceAndPath("minecraft", "enchanted_book"))
            .orElse(Items.AIR);
        return new ItemStack(fallback);
    }

    static final class MobList extends ContainerObjectSelectionList<MobList.Entry> {

        MobList(Minecraft mc, int width, int height, int top,
                List<EntityType<?>> entities, MobsScreen host) {
            super(mc, width, height, top, ENTRY_H);
            for (int i = 0; i < entities.size(); i += 2) {
                EntityType<?> left = entities.get(i);
                EntityType<?> right = i + 1 < entities.size() ? entities.get(i + 1) : null;
                addEntry(new PairEntry(left, right, host));
            }
        }

        @Override
        public int getRowWidth() { return width - 16; }

        @Override
        protected int getScrollbarPosition() { return width - 6; }

        abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {}

        /** Two mob cells per row — same layout shape as the selector grid. */
        static final class PairEntry extends Entry {

            private final Cell left;
            private final Cell right;

            PairEntry(EntityType<?> leftEntity, EntityType<?> rightEntity, MobsScreen host) {
                this.left = new Cell(leftEntity, host);
                this.right = rightEntity != null ? new Cell(rightEntity, host) : null;
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                List<NarratableEntry> out = new ArrayList<>(left.widgets());
                if (right != null) out.addAll(right.widgets());
                return out;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                List<GuiEventListener> out = new ArrayList<>(left.widgets());
                if (right != null) out.addAll(right.widgets());
                return out;
            }

            @Override
            public void render(GuiGraphics gfx, int idx, int rowTop, int rowLeft,
                               int rowWidth, int rowHeight, int mouseX, int mouseY,
                               boolean hovered, float partial) {
                int halfWidth = (rowWidth - GAP_BETWEEN) / 2;
                left.render(gfx, rowLeft, rowTop, halfWidth, rowHeight, mouseX, mouseY, partial);
                if (right != null) {
                    right.render(gfx, rowLeft + halfWidth + GAP_BETWEEN, rowTop,
                        halfWidth, rowHeight, mouseX, mouseY, partial);
                }
            }
        }

        /** One mob in the 2-column grid: icon · name · enabled. */
        static final class Cell {

            private final EntityType<?> entity;
            private final ResourceLocation entityId;
            private final MobsScreen host;
            private final Checkbox enabledBox;

            Cell(EntityType<?> entity, MobsScreen host) {
                this.entity = entity;
                this.entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity);
                this.host = host;

                boolean enabledNow = NamingConfig.isEntityEnabled(entity);
                Boolean pending = host.buffer().pendingEntityEnabled(entityId);
                if (pending != null) enabledNow = pending;
                this.enabledBox = Checkbox.builder(Component.literal(""), Minecraft.getInstance().font)
                    .pos(0, 0)
                    .selected(enabledNow)
                    .onValueChange((c, v) -> {
                        host.buffer().setEntityEnabled(entityId, v);
                    })
                    .build();
            }

            List<? extends net.minecraft.client.gui.components.AbstractWidget> widgets() {
                return List.of(enabledBox);
            }

            void render(GuiGraphics gfx, int cellLeft, int rowTop, int cellWidth, int rowHeight,
                        int mouseX, int mouseY, float partial) {
                int x = cellLeft + CELL_PAD;
                int iconY = rowTop + (rowHeight - ICON_SIZE) / 2;
                gfx.renderItem(iconForEntity(entity), x, iconY);
                int iconX = x;
                x += ICON_SIZE + CELL_GAP;

                int labelMaxWidth = cellLeft + cellWidth - CELL_PAD - CHECKBOX_SIZE - CELL_GAP - x;
                int labelY = rowTop + (rowHeight - 9) / 2;
                String label = entity.getDescription().getString();
                String trimmed = trimToWidth(label, labelMaxWidth);
                gfx.drawString(Minecraft.getInstance().font,
                    Component.literal(trimmed), x, labelY, 0xFFE0E0E0, false);

                enabledBox.setX(cellLeft + cellWidth - CELL_PAD - CHECKBOX_SIZE);
                enabledBox.setY(rowTop + (rowHeight - CHECKBOX_SIZE) / 2);
                enabledBox.render(gfx, mouseX, mouseY, partial);

                boolean hoverIcon = mouseX >= iconX && mouseX < iconX + ICON_SIZE
                    && mouseY >= iconY && mouseY < iconY + ICON_SIZE;
                if (hoverIcon && entityId != null) {
                    gfx.renderComponentTooltip(Minecraft.getInstance().font,
                        List.of(entity.getDescription(),
                                Component.literal(entityId.toString())),
                        mouseX, mouseY);
                }
            }

            private String trimToWidth(String text, int maxWidth) {
                var font = Minecraft.getInstance().font;
                if (font.width(text) <= maxWidth) return text;
                String ellipsis = "…";
                int ellipsisW = font.width(ellipsis);
                String acc = "";
                for (int i = 0; i < text.length(); i++) {
                    String next = text.substring(0, i + 1);
                    if (font.width(next) + ellipsisW > maxWidth) break;
                    acc = next;
                }
                return acc + ellipsis;
            }
        }
    }
}
