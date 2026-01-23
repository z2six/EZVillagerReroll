// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\VillagerUpdateTradesPreserveLocksMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.logic.TradeLockState;

/**
 * Hard guarantee: if a trade index is locked, the offer at that index must never change.
 *
 * Vanilla (and some mods) can call updateTrades() in ways that overwrite/mutate existing offers.
 * We keep an exact snapshot of the locked offers and re-apply them after updateTrades runs.
 */
@Mixin(Villager.class)
public abstract class VillagerUpdateTradesPreserveLocksMixin {

    @Inject(method = "updateTrades()V", at = @At("RETURN"), require = 0)
    private void ezvr$updateTrades$return(CallbackInfo ci) {
        try {
            Villager self = (Villager) (Object) this;
            MerchantOffers offers = self.getOffers();
            if (offers == null || offers.isEmpty()) return;

            long mask = TradeLockState.sanitizeMaskForSize(TradeLockState.getMask(self), offers.size());
            if (mask == 0L) return;

            TradeLockState.restoreLockedOffersFromSnapshots(self, offers);
            TradeLockState.sanitizeLockedOfferSnapshots(self, mask, offers.size());
        } catch (Throwable ignored) {}
    }
}

