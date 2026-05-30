package org.z2six.villageroverhaul.content;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.z2six.villageroverhaul.Constants;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Constants.MOD_ID);

    public static final DeferredHolder<Item, Item> TRADING_HALL =
            ITEMS.register("trading_hall", () -> new BlockItem(ModBlocks.TRADING_HALL.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> FAMILY_NAME_DEED =
            ITEMS.register("family_name_deed", () -> new Item(new Item.Properties().stacksTo(16)));

    private ModItems() {}

    public static void onBuildCreativeModeTabContents(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            e.accept(TRADING_HALL.get());
        }
        if (e.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            e.accept(FAMILY_NAME_DEED.get());
        }
    }
}
