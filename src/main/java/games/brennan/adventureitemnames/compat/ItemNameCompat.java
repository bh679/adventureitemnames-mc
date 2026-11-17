package games.brennan.adventureitemnames.compat;

//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
//?}
//? if <1.21.1 {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
*///?}

import games.brennan.adventureitemnames.internal.LoreReplace;
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

    /**
     * Copy {@code from}'s custom name and lore (when present) onto {@code to}, replacing
     * whatever {@code to} had. 1.21.1 copies the CUSTOM_NAME / LORE components; 1.20.1
     * the {@code display.Name} / {@code display.Lore} NBT.
     */
    public static void copyNameAndLore(ItemStack from, ItemStack to) {
        //? if >=1.21.1 {
        Component name = from.get(DataComponents.CUSTOM_NAME);
        if (name != null) to.set(DataComponents.CUSTOM_NAME, name);
        ItemLore lore = from.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) to.set(DataComponents.LORE, lore);
        //?} else {
        /*if (from.hasCustomHoverName()) to.setHoverName(from.getHoverName());
        CompoundTag fromDisplay = from.getTagElement("display");
        if (fromDisplay != null && fromDisplay.contains("Lore", Tag.TAG_LIST)) {
            ListTag lore = fromDisplay.getList("Lore", Tag.TAG_STRING);
            if (!lore.isEmpty()) to.getOrCreateTagElement("display").put("Lore", lore.copy());
        }*///?}
    }

    /**
     * Append {@code lines} as a replaceable lore entry tracked under {@code markerKey}: the
     * entry this stack last got under that key (its text kept in custom data) is removed
     * first, so re-applying swaps the line instead of stacking another. Other lore stays.
     * 1.21.1 uses the LORE + CUSTOM_DATA components; 1.20.1 the {@code display.Lore} NBT
     * and a root string tag.
     */
    public static void replaceTrackedLore(ItemStack stack, String markerKey, List<Component> lines, String text) {
        //? if >=1.21.1 {
        String previous = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString(markerKey);
        ItemLore existing = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> merged = LoreReplace.replaceLast(existing.lines(), previous, lines, Component::getString);
        if (merged.size() > MAX_LORE_LINES) merged = merged.subList(0, MAX_LORE_LINES);
        stack.set(DataComponents.LORE, new ItemLore(List.copyOf(merged)));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(markerKey, text));
        //?} else {
        /*CompoundTag root = stack.getTag();
        String previous = root == null ? "" : root.getString(markerKey);
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag loreTag = display.getList("Lore", Tag.TAG_STRING);
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < loreTag.size(); i++) existing.add(loreTag.getString(i));
        List<String> added = new ArrayList<>();
        for (Component line : lines) added.add(Component.Serializer.toJson(line));
        List<String> merged = LoreReplace.replaceLast(existing, previous, added, ItemNameCompat::loreJsonText);
        ListTag out = new ListTag();
        for (String json : merged) {
            if (out.size() >= MAX_LORE_LINES) break;
            out.add(StringTag.valueOf(json));
        }
        display.put("Lore", out);
        stack.getOrCreateTag().putString(markerKey, text);*///?}
    }

    //? if <1.21.1 {
    /*private static String loreJsonText(String json) {
        try {
            Component parsed = Component.Serializer.fromJson(json);
            return parsed == null ? "" : parsed.getString();
        } catch (RuntimeException e) {
            return "";
        }
    }*///?}
}
