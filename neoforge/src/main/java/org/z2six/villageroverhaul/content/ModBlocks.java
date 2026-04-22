package org.z2six.villageroverhaul.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.block.TradingHallBlock;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Constants.MOD_ID);

    public static final DeferredBlock<Block> TRADING_HALL = BLOCKS.register(
            "trading_hall",
            () -> new TradingHallBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.5F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            )
    );

    private ModBlocks() {}
}
