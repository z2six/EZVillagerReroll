// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/TradeUtil.java
package org.z2six.ezvillagerreroll.logic;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.AbstractVillagerAccessor;
import org.z2six.ezvillagerreroll.mixin.VillagerAccessor;

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
     * Must preserve trade locks.
     */
    public static boolean rebuildOffersInternal(Villager vill, ServerPlayer player, boolean syncToPlayer) {
        try {
            if (vill == null) return false;

            final VillagerData original = vill.getVillagerData();
            final int targetLevel = Math.max(1, Math.min(5, original.getLevel()));
            final int oldSize = vill.getOffers() != null ? vill.getOffers().size() : -1;

            // Snapshot old offers (needed to preserve locked indices)
            MerchantOffers oldOffers = null;
            try {
                if (vill.getOffers() != null) {
                    oldOffers = new MerchantOffers();
                    for (MerchantOffer o : vill.getOffers()) oldOffers.add(o);
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] Failed to snapshot old offers (villager={}) {}", vill.getUUID(), t.toString());
            }

            // Clear and rebuild per level
            ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(new MerchantOffers());

            for (int l = 1; l <= targetLevel; l++) {
                VillagerData step = new VillagerData(original.getType(), original.getProfession(), l);
                vill.setVillagerData(step);
                ((VillagerAccessor) vill).ezvr$updateTrades(); // appends that level's offers
            }

            vill.setVillagerData(original);

            // Only apply special prices if player is known
            try {
                if (player != null) ((VillagerAccessor) vill).ezvr$updateSpecialPrices(player);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] updateSpecialPrices skipped/failed (soft): {}", t.toString());
            }

            MerchantOffers offers = vill.getOffers();
            int newSize = offers != null ? offers.size() : -1;

            // Preserve locked trades
            long beforeMask = TradeLockState.getMask(vill);
            long afterMask = TradeLockState.sanitizeMaskForSize(beforeMask, (offers == null ? 0 : offers.size()));
            if (afterMask != beforeMask) {
                TradeLockState.setMask(vill, afterMask);
                EZVillagerReroll.LOG().debug("[EZVR] Lock mask sanitized due to offer size change (villager={} before={} after={})",
                        vill.getUUID(), Long.toUnsignedString(beforeMask), Long.toUnsignedString(afterMask));
            }

            if (offers != null && oldOffers != null && afterMask != 0L) {
                int limit = Math.min(offers.size(), oldOffers.size());
                int preserved = 0;

                for (int i = 0; i < limit; i++) {
                    if ((afterMask & (1L << i)) == 0L) continue;

                    try {
                        MerchantOffer old = oldOffers.get(i);
                        if (old != null) {
                            offers.set(i, old);
                            preserved++;
                        }
                    } catch (Throwable t) {
                        EZVillagerReroll.LOG().warn("[EZVR] Failed to preserve locked offer idx={} villager={} {}", i, vill.getUUID(), t.toString());
                    }
                }
            }

            // Optional GUI sync
            if (syncToPlayer && player != null && player.containerMenu instanceof MerchantMenu menu) {
                player.sendMerchantOffers(
                        menu.containerId,
                        offers,
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );

                EZVillagerReroll.LOG().info(
                        "[EZVR] Rebuilt offers via vanilla steps: villager={}, level={}, offers {} -> {}",
                        vill.getUUID(), targetLevel, oldSize, newSize
                );
            } else {
                EZVillagerReroll.LOG().debug(
                        "[EZVR] Rebuilt offers (no GUI sync): villager={}, level={}, offers {} -> {}",
                        vill.getUUID(), targetLevel, oldSize, newSize
                );
            }

            // If we changed/sanitized locks, push updated locks to active traders so UI stays consistent.
            if (afterMask != beforeMask) {
                try {
                    org.z2six.ezvillagerreroll.server.TradeLockSyncService.syncToActiveTraders(vill, afterMask);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] Failed to sync sanitized lock mask to active traders: {}", t.toString());
                }
            }

            // ---- NEW: Persist canonical offers after every rebuild (manual reroll + auto-search reroll) ----
            try {
                var level = vill.level();
                if (level != null && !level.isClientSide()) {
                    var data = org.z2six.ezvillagerreroll.server.VillagerOffersSavedData.get(level);
                    if (data != null) {
                        data.capture(vill);
                        EZVillagerReroll.LOG().debug("[EZVR] Persisted rebuilt offers to world data (villager={}, offers={})",
                                vill.getUUID(), (vill.getOffers() == null ? -1 : vill.getOffers().size()));
                    }
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Persist rebuilt offers failed (soft): {}", t.toString());
            }

            return true;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] rebuildOffersInternal exception", t);
            return false;
        }
    }

    private TradeUtil() {}
}
