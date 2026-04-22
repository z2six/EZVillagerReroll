package org.z2six.villageroverhaul.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.z2six.villageroverhaul.content.ModBlockEntities;

public final class TradingHallBlockEntity extends RandomizableContainerBlockEntity implements WorldlyContainer {

    public static final int CONTAINER_SIZE = 27;
    private static final int[] SLOTS = buildSlots();

    private NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);

    public TradingHallBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.TRADING_HALL.get(), pos, blockState);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.villageroverhaul.trading_hall");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> stacks) {
        items = stacks;
    }

    @Override
    public int getContainerSize() {
        return CONTAINER_SIZE;
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return ChestMenu.threeRows(containerId, playerInventory, this);
    }

    @Override
    public void loadAdditional(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (level == null || player == null) return false;
        if (level.getBlockEntity(worldPosition) != this) return false;
        double cx = worldPosition.getX() + 0.5D;
        double cy = worldPosition.getY() + 0.5D;
        double cz = worldPosition.getZ() + 0.5D;
        return player.distanceToSqr(cx, cy, cz) <= 64.0D;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int index, ItemStack stack, @Nullable Direction direction) {
        return true;
    }

    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return true;
    }

    private static int[] buildSlots() {
        int[] slots = new int[CONTAINER_SIZE];
        for (int i = 0; i < CONTAINER_SIZE; i++) {
            slots[i] = i;
        }
        return slots;
    }
}
