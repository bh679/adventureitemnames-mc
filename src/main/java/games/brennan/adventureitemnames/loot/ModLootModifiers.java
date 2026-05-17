package games.brennan.adventureitemnames.loot;

import com.mojang.serialization.MapCodec;
import games.brennan.adventureitemnames.AdventureItemNames;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Registers the GLM serializer codec so loot table modifiers can reference it. */
public final class ModLootModifiers {

    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, AdventureItemNames.MOD_ID);

    public static final Supplier<MapCodec<NameLootModifier>> NAME_ITEMS =
        SERIALIZERS.register("name_items", () -> NameLootModifier.CODEC);

    private ModLootModifiers() {}

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}
