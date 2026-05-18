package games.brennan.adventureitemnames.item;

import games.brennan.adventureitemnames.api.NameComposer;
import games.brennan.adventureitemnames.internal.NameRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.List;

/**
 * Test item: right-click a block face to place a vanilla chest filled with
 * AIN-named stacks in every slot. OP-gated. Intended for iterating on
 * naming output — one click yields a chestful of named items across the
 * full pool of selector-matched items.
 *
 * <p>Nameable pool is rebuilt per placement by probing every entry in
 * {@code BuiltInRegistries.ITEM} against
 * {@link NameRegistry#findMatching(ItemStack)} — so selector edits picked
 * up by {@code /reload} reflect on the next placement.</p>
 *
 * <p>Uses {@link NameRegistry#findMatching(ItemStack)} from the
 * {@code internal} package by design: AIN is its own consumer here, and
 * {@code findMatching} is the canonical "is this nameable?" probe.</p>
 */
public final class RandomChestItem extends Item {

    public RandomChestItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.FAIL;

        if (!player.hasPermissions(2)) {
            player.displayClientMessage(
                Component.literal("Random Chest is OP-only").withStyle(ChatFormatting.RED),
                true
            );
            return InteractionResult.FAIL;
        }

        BlockPos target = pickTarget(level, ctx.getClickedPos(), ctx.getClickedFace());
        if (target == null) {
            player.displayClientMessage(
                Component.literal("No replaceable block to place chest").withStyle(ChatFormatting.YELLOW),
                true
            );
            return InteractionResult.FAIL;
        }

        BlockState chestState = Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.FACING, player.getDirection().getOpposite())
            .setValue(ChestBlock.TYPE, ChestType.SINGLE);
        level.setBlock(target, chestState, Block.UPDATE_ALL);

        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof ChestBlockEntity chest) {
            fillChest(chest, level.getRandom());
        }

        if (!player.getAbilities().instabuild) {
            ctx.getItemInHand().shrink(1);
        }

        return InteractionResult.CONSUME;
    }

    private static BlockPos pickTarget(Level level, BlockPos clicked, Direction face) {
        BlockPos neighbour = clicked.relative(face);
        if (level.getBlockState(neighbour).canBeReplaced()) return neighbour;
        if (level.getBlockState(clicked).canBeReplaced()) return clicked;
        return null;
    }

    private static void fillChest(ChestBlockEntity chest, RandomSource rng) {
        List<Item> pool = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack probe = new ItemStack(item, 1);
            if (NameRegistry.findMatching(probe).isPresent()) {
                pool.add(item);
            }
        }
        if (pool.isEmpty()) return;

        int size = chest.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            Item pick = pool.get(rng.nextInt(pool.size()));
            ItemStack stack = new ItemStack(pick, 1);
            NameComposer.applyName(stack, rng);
            chest.setItem(slot, stack);
        }
        chest.setChanged();
    }
}
