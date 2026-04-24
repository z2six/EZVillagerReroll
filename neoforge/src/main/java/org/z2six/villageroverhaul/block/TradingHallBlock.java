package org.z2six.villageroverhaul.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.z2six.villageroverhaul.block.entity.TradingHallBlockEntity;
import org.z2six.villageroverhaul.content.ModBlockEntities;
import org.z2six.villageroverhaul.logic.EmeraldPouchBridge;

public final class TradingHallBlock extends BaseEntityBlock implements EntityBlock {

    public static final MapCodec<TradingHallBlock> CODEC = simpleCodec(TradingHallBlock::new);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final AABB EMERALD_PICKUP_ZONE = new AABB(0.24D, 0.96D, 0.24D, 0.76D, 1.18D, 0.76D);

    public TradingHallBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context == null ? Direction.NORTH : context.getHorizontalDirection().getOpposite();
        return this.defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TradingHallBlockEntity hall) {
                if (tryCollectFromEmeraldPile(level, pos, sp, hall, hit)) {
                    return InteractionResult.CONSUME;
                }
                sp.openMenu(hall);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TradingHallBlockEntity hall && tryCollectFromEmeraldPile(level, pos, sp, hall, hit)) {
                return ItemInteractionResult.CONSUME;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TradingHallBlockEntity hall) {
                Containers.dropContents(level, pos, hall);
                int storedEmeralds = hall.clearStoredEmeralds();
                while (storedEmeralds > 0) {
                    int batch = Math.min(64, storedEmeralds);
                    Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, new ItemStack(Items.EMERALD, batch));
                    storedEmeralds -= batch;
                }
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof TradingHallBlockEntity hall) {
            return net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(hall);
        }
        return 0;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TradingHallBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    private static void payoutStoredEmeralds(Level level, BlockPos pos, ServerPlayer player, TradingHallBlockEntity hall) {
        int emeralds = hall.clearStoredEmeralds();
        if (emeralds <= 0) return;
        if (EmeraldPouchBridge.give(player, emeralds)) return;

        while (emeralds > 0) {
            int batch = Math.min(64, emeralds);
            ItemStack payout = new ItemStack(Items.EMERALD, batch);
            boolean added = player.getInventory().add(payout);
            if (!added && !payout.isEmpty()) {
                Containers.dropItemStack(level, player.getX(), player.getY(), player.getZ(), payout);
            }
            emeralds -= batch;
        }
    }

    public static boolean isPileHit(BlockPos pos, Vec3 hitLocation) {
        try {
            if (pos == null || hitLocation == null) return false;
            Vec3 local = hitLocation.subtract(pos.getX(), pos.getY(), pos.getZ());
            return EMERALD_PICKUP_ZONE.contains(local);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPileHit(BlockHitResult hit) {
        try {
            if (hit == null) return false;
            return isPileHit(hit.getBlockPos(), hit.getLocation());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryCollectFromEmeraldPile(Level level, BlockPos pos, ServerPlayer player, TradingHallBlockEntity hall, BlockHitResult hit) {
        try {
            if (level == null || pos == null || player == null || hall == null || hit == null) return false;
            if (!hall.hasStoredEmeralds()) return false;
            if (!isPileHit(pos, hit.getLocation())) return false;
            payoutStoredEmeralds(level, pos, player, hall);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
