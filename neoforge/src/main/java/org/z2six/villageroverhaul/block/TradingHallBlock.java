package org.z2six.villageroverhaul.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.z2six.villageroverhaul.block.entity.TradingHallBlockEntity;
import org.z2six.villageroverhaul.content.ModBlockEntities;

public final class TradingHallBlock extends BaseEntityBlock implements EntityBlock {

    public static final MapCodec<TradingHallBlock> CODEC = simpleCodec(TradingHallBlock::new);

    public TradingHallBlock(BlockBehaviour.Properties properties) {
        super(properties);
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
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TradingHallBlockEntity hall) {
                sp.openMenu(hall);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof TradingHallBlockEntity hall) {
                Containers.dropContents(level, pos, hall);
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
}
