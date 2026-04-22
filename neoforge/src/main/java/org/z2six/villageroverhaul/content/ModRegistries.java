package org.z2six.villageroverhaul.content;

import net.neoforged.bus.api.IEventBus;

public final class ModRegistries {

    private ModRegistries() {}

    public static void register(IEventBus modBus) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(ModItems::onBuildCreativeModeTabContents);
    }
}
