// neoforge\src\main\java\org\z2six\villageroverhaul\logic\TradeUtil.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.AbstractVillagerAccessor;
import org.z2six.villageroverhaul.mixin.VillagerAccessor;

public final class TradeUtil {

    public static boolean rebuildOffers(Villager vill, ServerPlayer player) {
        return rebuildOffersInternal(vill, player, true);
    }

    /**
     * Internal offer rebuild used by:
     * - normal reroll (syncToPlayer=true)
     * - auto-search rerolls (syncToPlayer=false)
     * - catalog sampling (syncToPlayer=false)
     *
     * This path intentionally does a single rebuild only. Duplicate offers are valid.
     */
    public static boolean rebuildOffersInternal(Villager vill, ServerPlayer player, boolean syncToPlayer) {
        try {
            if (vill == null) return false;

            final VillagerData original = vill.getVillagerData();
            final int targetLevel = Math.max(1, Math.min(5, original.getLevel()));
            final int offersBefore = vill.getOffers() != null ? vill.getOffers().size() : -1;

            final MerchantOffers oldOffersSnapshot = snapshotOffersSafe(vill);
            final long beforeMask = TradeLockState.getMask(vill);

            MerchantOffers finalOffers = rebuildOffersVanillaSteps(vill, player, original, targetLevel);

            // IMPORTANT:
            // Do NOT sanitize/persist the lock mask against this intermediate rebuilt size.
            // Hoarder may immediately expand the list again, and tail locks must survive that round-trip.
            long rebuildSizedMask = TradeLockState.sanitizeMaskForSize(beforeMask, finalOffers == null ? 0 : finalOffers.size());

            if (finalOffers != null && oldOffersSnapshot != null && rebuildSizedMask != 0L) {
                restoreLockedOffersSafe(finalOffers, oldOffersSnapshot, rebuildSizedMask, vill);
            }

            try { TradeLockState.restoreLockedOffersFromSnapshots(vill, finalOffers); } catch (Throwable ignored) {}

            try {
                if (finalOffers != null && vill.getOffers() != finalOffers) {
                    ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(finalOffers);
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Failed to force-set final offers reference (soft): {}", t.toString());
            }

            if (syncToPlayer && player != null && player.containerMenu instanceof MerchantMenu menu) {
                MerchantOffers offers = vill.getOffers();
                int offersAfter = offers != null ? offers.size() : -1;

                player.sendMerchantOffers(
                        menu.containerId,
                        offers,
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Rebuilt offers: villager={}, level={}, offers {} -> {}",
                        vill.getUUID(), targetLevel, offersBefore, offersAfter
                );
            } else {
                MerchantOffers offers = vill.getOffers();
                int offersAfter = offers != null ? offers.size() : -1;

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Rebuilt offers (no GUI sync): villager={}, level={}, offers {} -> {}",
                        vill.getUUID(), targetLevel, offersBefore, offersAfter
                );
            }

            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] rebuildOffersInternal exception", t);
            return false;
        }
    }

    private static MerchantOffers rebuildOffersVanillaSteps(Villager vill, ServerPlayer player, VillagerData original, int targetLevel) {
        try {
            ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(new MerchantOffers());

            for (int l = 1; l <= targetLevel; l++) {
                VillagerData step = new VillagerData(original.getType(), original.getProfession(), l);
                vill.setVillagerData(step);
                ((VillagerAccessor) vill).ezvr$updateTrades();
            }

            vill.setVillagerData(original);

            try {
                if (player != null) ((VillagerAccessor) vill).ezvr$updateSpecialPrices(player);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] updateSpecialPrices skipped/failed (soft): {}", t.toString());
            }

            return vill.getOffers();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] rebuildOffersVanillaSteps failed (villager={}): {}", vill == null ? "null" : vill.getUUID(), t.toString());
            return vill != null ? vill.getOffers() : null;
        }
    }

    private static MerchantOffers snapshotOffersSafe(Villager vill) {
        try {
            if (vill == null || vill.getOffers() == null) return null;

            MerchantOffers snap = new MerchantOffers();
            for (MerchantOffer o : vill.getOffers()) {
                if (o != null) snap.add(o);
            }
            return snap;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] Failed to snapshot old offers (villager={}): {}", vill == null ? "null" : vill.getUUID(), t.toString());
            return null;
        }
    }

    private static void restoreLockedOffersSafe(MerchantOffers current, MerchantOffers oldOffers, long mask, Villager vill) {
        try {
            if (current == null || oldOffers == null || mask == 0L) return;

            int limit = Math.min(current.size(), oldOffers.size());
            int preserved = 0;

            for (int i = 0; i < limit; i++) {
                if ((mask & (1L << i)) == 0L) continue;

                try {
                    MerchantOffer old = oldOffers.get(i);
                    if (old != null) {
                        try {
                            MerchantOffer cur = current.get(i);
                            TradeLockState.preserveRuntimeState(cur, old);
                        } catch (Throwable ignored) {}
                        current.set(i, old);
                        preserved++;
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] Failed to preserve locked offer idx={} villager={} {}",
                            i, vill == null ? "null" : vill.getUUID(), t.toString());
                }
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Preserved {} locked offers (villager={}, mask={})",
                    preserved, vill == null ? "null" : vill.getUUID(), Long.toUnsignedString(mask));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] restoreLockedOffersSafe failed (soft): {}", t.toString());
        }
    }

    private TradeUtil() {}
}
