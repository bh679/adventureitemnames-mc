package games.brennan.adventureitemnames.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.adventureitemnames.api.NameComposer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * Global Loot Modifier that runs after every vanilla loot table roll and
 * applies procedural naming to each item the table dropped.
 *
 * <p>This is what makes the mod useful out of the box: install it
 * standalone and vanilla chest/mob/fishing loot starts coming out with
 * generated names — no other mods or scripts required. Other mods can
 * still call {@link NameComposer#applyName} directly from their own
 * custom loot pipelines.</p>
 */
public class NameLootModifier extends LootModifier {

    public static final MapCodec<NameLootModifier> CODEC =
        RecordCodecBuilder.mapCodec(inst -> LootModifier.codecStart(inst)
            .apply(inst, NameLootModifier::new));

    public NameLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        RandomSource rng = context.getRandom();
        for (ItemStack stack : generatedLoot) {
            NameComposer.applyName(stack, rng);
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
