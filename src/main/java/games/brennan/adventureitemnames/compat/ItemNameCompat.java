package games.brennan.adventureitemnames.compat;

//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
//?}
//? if <1.21.1 {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
*///?}

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Version-bridging item display helpers. MC 1.20.5 replaced item NBT with the
 * Data Components system: 1.21.1 stamps the custom name and lore as
 * {@code DataComponents.CUSTOM_NAME} / {@code DataComponents.LORE} components,
 * whereas 1.20.1 writes the vanilla {@code display.Name} / {@code display.Lore}
 * NBT (lore lines are JSON-serialised components). Both render identically in the
 * tooltip. Concentrating the split here keeps {@code NameComposer} version-agnostic.
 */
public final class ItemNameCompat {

    /** Vanilla's lore-line cap (1.21's {@code ItemLore.MAX_LINES}). */
    private static final int MAX_LORE_LINES = 256;

    private ItemNameCompat() {}

    /** Stamp a custom display name. 1.21.1 CUSTOM_NAME component; 1.20.1 hover-name NBT. */
    public static void setCustomName(ItemStack stack, Component name) {
        //? if >=1.21.1 {
        stack.set(DataComponents.CUSTOM_NAME, name);
        //?} else {
        /*stack.setHoverName(name);
        *///?}
    }

    /**
     * Append {@code lines} to the stack's existing lore (preserving any present),
     * clamped to vanilla's {@value #MAX_LORE_LINES}-line cap. 1.21.1 merges into the
     * LORE component; 1.20.1 appends JSON-serialised components to the
     * {@code display.Lore} NBT list.
     */
    public static void appendLore(ItemStack stack, List<Component> lines) {
        //? if >=1.21.1 {
        ItemLore existing = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> merged = new ArrayList<>(existing.lines());
        for (Component line : lines) {
            if (merged.size() >= MAX_LORE_LINES) break;
            merged.add(line);
        }
        stack.set(DataComponents.LORE, new ItemLore(List.copyOf(merged)));
        //?} else {
        /*CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag loreTag = display.getList("Lore", Tag.TAG_STRING);
        for (Component line : lines) {
            if (loreTag.size() >= MAX_LORE_LINES) break;
            loreTag.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        }
        display.put("Lore", loreTag);*///?}
    }
}
