// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\MerchantMenuAccessor.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantMenu.class)
public interface MerchantMenuAccessor {

    @Accessor("trader")
    Merchant ezvr$getTrader();
}
