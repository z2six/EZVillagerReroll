// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\MerchantScreenAccessor.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access private scrolling state on MerchantScreen so we can map visible row -> absolute offer index.
 *
 * Vanilla uses a fixed set of row buttons (0..6) and a scroll offset to decide which offers they represent.
 */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {

    /**
     * The scroll offset in the offer list. In Mojmap 1.21.x this is typically named "scrollOff".
     * If mappings change, the accessor may fail at runtime, so callers should fall back defensively.
     */
    @Accessor("scrollOff")
    int ezvr$getScrollOff();

    @Accessor("tradeOfferButtons")
    Object[] ezvr$getTradeOfferButtons();
}
