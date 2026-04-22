package org.z2six.villageroverhaul.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.block.entity.TradingHallBlockEntity;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Constants.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TradingHallBlockEntity>> TRADING_HALL =
            BLOCK_ENTITY_TYPES.register(
                    "trading_hall",
                    () -> BlockEntityType.Builder.of(TradingHallBlockEntity::new, ModBlocks.TRADING_HALL.get()).build(null)
            );

    private ModBlockEntities() {}
}
