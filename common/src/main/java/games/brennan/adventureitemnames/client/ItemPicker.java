package games.brennan.adventureitemnames.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Two-stage modal picker rendered on top of a host screen. The user
 * first picks an item <em>kind</em> (sword / axe / pickaxe / shovel /
 * hoe / bow / crossbow / helmet / chestplate / leggings / boots /
 * shield), then a <em>material</em> where applicable. Items without a
 * material variant (bow, crossbow, shield) skip stage 2 and resolve
 * immediately.
 *
 * <p>The picker carries its own bounds + hit-testing — the host screen
 * delegates {@link #mouseClicked} and {@link #render} but no other
 * input handling is needed. A click outside the panel bounds cancels
 * the picker; the host screen converts that to a "close picker" via
 * the {@link Listener#onCancelled} callback.
 */
@Environment(EnvType.CLIENT)
public final class ItemPicker {

    public interface Listener {
        /** User picked a specific kind + material (or a fixed-item kind). */
        void onSpecific(ItemStack stack);
        /** User wants a fully random item — re-roll kind + material every name re-roll. */
        void onRandomItem();
        /** User wants a fixed kind but random material — re-roll material every name re-roll. */
        void onRandomMaterial(Kind kind);
        void onCancelled();
    }

    public enum Kind {
        SWORD("sword", "Sword"),
        AXE("axe", "Axe"),
        PICKAXE("pickaxe", "Pickaxe"),
        SHOVEL("shovel", "Shovel"),
        HOE("hoe", "Hoe"),
        BOW("bow", "Bow"),
        CROSSBOW("crossbow", "Crossbow"),
        HELMET("helmet", "Helmet"),
        CHESTPLATE("chestplate", "Chestplate"),
        LEGGINGS("leggings", "Leggings"),
        BOOTS("boots", "Boots"),
        SHIELD("shield", "Shield");

        public final String pathSuffix;
        public final String label;
        Kind(String pathSuffix, String label) {
            this.pathSuffix = pathSuffix;
            this.label = label;
        }
        public boolean isFixed() { return this == BOW || this == CROSSBOW || this == SHIELD; }
        public boolean isArmor() {
            return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
        }
        public Item iconItem() {
            String path = isFixed() ? pathSuffix : "iron_" + pathSuffix;
            return BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath("minecraft", path)).orElse(Items.AIR);
        }
        public List<Material> materials() {
            if (isFixed()) return List.of();
            return isArmor() ? ARMOR_MATERIALS : TOOL_MATERIALS;
        }
    }

    public enum Material {
        WOOD("wooden_", "Wood"),
        STONE("stone_", "Stone"),
        IRON("iron_", "Iron"),
        GOLD("golden_", "Gold"),
        DIAMOND("diamond_", "Diamond"),
        NETHERITE("netherite_", "Netherite"),
        LEATHER("leather_", "Leather"),
        CHAINMAIL("chainmail_", "Chainmail");

        public final String pathPrefix;
        public final String label;
        Material(String pathPrefix, String label) {
            this.pathPrefix = pathPrefix;
            this.label = label;
        }
    }

    private static final List<Material> TOOL_MATERIALS =
        List.of(Material.WOOD, Material.STONE, Material.IRON, Material.GOLD, Material.DIAMOND, Material.NETHERITE);
    private static final List<Material> ARMOR_MATERIALS =
        List.of(Material.LEATHER, Material.CHAINMAIL, Material.IRON, Material.GOLD, Material.DIAMOND, Material.NETHERITE);

    public static ItemStack resolve(Kind k, Material m) {
        if (k.isFixed()) return new ItemStack(k.iconItem());
        String path = m.pathPrefix + k.pathSuffix;
        Item item = BuiltInRegistries.ITEM.getOptional(
            ResourceLocation.fromNamespaceAndPath("minecraft", path)).orElse(null);
        if (item == null || item == Items.AIR) return new ItemStack(k.iconItem());
        return new ItemStack(item);
    }

    // Layout constants
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 124;
    private static final int CELL = 28;
    private static final int ICON = 16;
    private static final int ICON_PAD = (CELL - ICON) / 2;
    private static final int BTN_W = 56;
    private static final int BTN_H = 18;

    private enum Stage { PICK_KIND, PICK_MATERIAL }

    private final Listener listener;
    private final int screenW;
    private final int screenH;
    private final int panelX;
    private final int panelY;
    private Stage stage = Stage.PICK_KIND;
    private Kind selectedKind;

    public ItemPicker(int screenW, int screenH, Listener listener) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.panelX = (screenW - PANEL_W) / 2;
        this.panelY = (screenH - PANEL_H) / 2;
        this.listener = listener;
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.fill(0, 0, screenW, screenH, 0xA0000000);
        gfx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF1A1A1A);
        gfx.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY,             0xFF707070);
        gfx.fill(panelX - 1, panelY + PANEL_H, panelX + PANEL_W + 1, panelY + PANEL_H + 1, 0xFF707070);
        gfx.fill(panelX - 1, panelY, panelX, panelY + PANEL_H,                       0xFF707070);
        gfx.fill(panelX + PANEL_W, panelY, panelX + PANEL_W + 1, panelY + PANEL_H,   0xFF707070);

        String title = stage == Stage.PICK_KIND
            ? "Pick item type"
            : "Pick material — " + selectedKind.label;
        gfx.drawCenteredString(Minecraft.getInstance().font,
            Component.literal(title), panelX + PANEL_W / 2, panelY + 8, 0xFFFFFFFF);

        if (stage == Stage.PICK_KIND) renderKindGrid(gfx, mouseX, mouseY);
        else renderMaterialGrid(gfx, mouseX, mouseY);

        int btnY = panelY + PANEL_H - BTN_H - 6;
        drawButton(gfx, "Cancel", panelX + PANEL_W - BTN_W - 8, btnY, mouseX, mouseY);
        if (stage == Stage.PICK_MATERIAL) drawButton(gfx, "Back", panelX + 8, btnY, mouseX, mouseY);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (!inPanel(mouseX, mouseY)) {
            listener.onCancelled();
            return true;
        }
        int btnY = panelY + PANEL_H - BTN_H - 6;
        if (hitButton(mouseX, mouseY, panelX + PANEL_W - BTN_W - 8, btnY)) {
            listener.onCancelled();
            return true;
        }
        if (stage == Stage.PICK_MATERIAL && hitButton(mouseX, mouseY, panelX + 8, btnY)) {
            stage = Stage.PICK_KIND;
            selectedKind = null;
            return true;
        }
        if (stage == Stage.PICK_KIND) return handleKindClick(mouseX, mouseY);
        return handleMaterialClick(mouseX, mouseY);
    }

    public boolean keyPressed(int keyCode) {
        // GLFW_KEY_ESCAPE = 256
        if (keyCode == 256) {
            listener.onCancelled();
            return true;
        }
        return false;
    }

    private boolean inPanel(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + PANEL_W
            && mouseY >= panelY && mouseY < panelY + PANEL_H;
    }

    private boolean hitButton(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;
    }

    private void drawButton(GuiGraphics gfx, String label, int x, int y, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + BTN_W && mouseY >= y && mouseY < y + BTN_H;
        gfx.fill(x, y, x + BTN_W, y + BTN_H, hover ? 0xFF505050 : 0xFF383838);
        gfx.fill(x, y, x + BTN_W, y + 1, 0xFF8C8C8C);
        gfx.fill(x, y + BTN_H - 1, x + BTN_W, y + BTN_H, 0xFF181818);
        gfx.drawCenteredString(Minecraft.getInstance().font,
            Component.literal(label), x + BTN_W / 2, y + 5, 0xFFFFFFFF);
    }

    private int gridCols() { return stage == Stage.PICK_KIND ? 7 : 4; }
    private int gridOriginX() {
        int gridW = gridCols() * CELL;
        return panelX + (PANEL_W - gridW) / 2;
    }
    private int gridOriginY() { return panelY + 24; }

    private void renderKindGrid(GuiGraphics gfx, int mouseX, int mouseY) {
        Kind[] kinds = Kind.values();
        int gridX = gridOriginX();
        int gridY = gridOriginY();
        int cols = gridCols();
        for (int i = 0; i < kinds.length; i++) {
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL;
            drawCellWithIcon(gfx, x, y, new ItemStack(kinds[i].iconItem()), mouseX, mouseY, kinds[i].label);
        }
        int ri = kinds.length;
        int rx = gridX + (ri % cols) * CELL;
        int ry = gridY + (ri / cols) * CELL;
        drawRandomCell(gfx, rx, ry, mouseX, mouseY, "Random item");
    }

    private boolean handleKindClick(double mouseX, double mouseY) {
        Kind[] kinds = Kind.values();
        int gridX = gridOriginX();
        int gridY = gridOriginY();
        int cols = gridCols();
        for (int i = 0; i < kinds.length; i++) {
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                Kind picked = kinds[i];
                if (picked.isFixed()) {
                    listener.onSpecific(new ItemStack(picked.iconItem()));
                } else {
                    selectedKind = picked;
                    stage = Stage.PICK_MATERIAL;
                }
                return true;
            }
        }
        int ri = kinds.length;
        int rx = gridX + (ri % cols) * CELL;
        int ry = gridY + (ri / cols) * CELL;
        if (mouseX >= rx && mouseX < rx + CELL && mouseY >= ry && mouseY < ry + CELL) {
            listener.onRandomItem();
            return true;
        }
        return true;
    }

    private void renderMaterialGrid(GuiGraphics gfx, int mouseX, int mouseY) {
        List<Material> mats = selectedKind.materials();
        int gridX = gridOriginX();
        int gridY = gridOriginY();
        int cols = gridCols();
        for (int i = 0; i < mats.size(); i++) {
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL;
            ItemStack stack = resolve(selectedKind, mats.get(i));
            drawCellWithIcon(gfx, x, y, stack, mouseX, mouseY,
                mats.get(i).label + " " + selectedKind.label);
        }
        int ri = mats.size();
        int rx = gridX + (ri % cols) * CELL;
        int ry = gridY + (ri / cols) * CELL;
        drawRandomCell(gfx, rx, ry, mouseX, mouseY, "Random material");
    }

    private boolean handleMaterialClick(double mouseX, double mouseY) {
        List<Material> mats = selectedKind.materials();
        int gridX = gridOriginX();
        int gridY = gridOriginY();
        int cols = gridCols();
        for (int i = 0; i < mats.size(); i++) {
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                listener.onSpecific(resolve(selectedKind, mats.get(i)));
                return true;
            }
        }
        int ri = mats.size();
        int rx = gridX + (ri % cols) * CELL;
        int ry = gridY + (ri / cols) * CELL;
        if (mouseX >= rx && mouseX < rx + CELL && mouseY >= ry && mouseY < ry + CELL) {
            listener.onRandomMaterial(selectedKind);
            return true;
        }
        return true;
    }

    private void drawRandomCell(GuiGraphics gfx, int cellX, int cellY,
                                int mouseX, int mouseY, String tooltip) {
        boolean hover = mouseX >= cellX && mouseX < cellX + CELL
            && mouseY >= cellY && mouseY < cellY + CELL;
        if (hover) gfx.fill(cellX, cellY, cellX + CELL, cellY + CELL, 0x40FFFFFF);
        int ix = cellX + ICON_PAD;
        int iy = cellY + ICON_PAD;
        gfx.fill(ix, iy, ix + ICON, iy + ICON, 0xFF6A4A8A);
        gfx.fill(ix, iy, ix + ICON, iy + 1, 0xFF8A6AAA);
        gfx.fill(ix, iy + ICON - 1, ix + ICON, iy + ICON, 0xFF402068);
        gfx.drawCenteredString(Minecraft.getInstance().font,
            Component.literal("?"), cellX + CELL / 2, iy + 4, 0xFFFFFFFF);
        if (hover && tooltip != null) {
            gfx.renderTooltip(Minecraft.getInstance().font,
                Component.literal(tooltip), mouseX, mouseY);
        }
    }

    private void drawCellWithIcon(GuiGraphics gfx, int cellX, int cellY,
                                  ItemStack stack, int mouseX, int mouseY, String tooltip) {
        boolean hover = mouseX >= cellX && mouseX < cellX + CELL
            && mouseY >= cellY && mouseY < cellY + CELL;
        if (hover) gfx.fill(cellX, cellY, cellX + CELL, cellY + CELL, 0x40FFFFFF);
        gfx.renderItem(stack, cellX + ICON_PAD, cellY + ICON_PAD);
        if (hover && tooltip != null) {
            gfx.renderTooltip(Minecraft.getInstance().font,
                Component.literal(tooltip), mouseX, mouseY);
        }
    }
}
