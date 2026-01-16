// MainFile: src/main/java/org/z2six/villageroverhaul/server/TooltipService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.logic.MoneyBridge;
import org.z2six.villageroverhaul.logic.TradeLockState;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipData;
import org.z2six.villageroverhaul.logic.RerollState;

public final class TooltipService {

    public static PacketTooltipData computeSnapshot(ServerPlayer player, int traderEntityId) {
        var out = new PacketTooltipData();
        try {
            Villager vill = null;

            // 1) Primary: resolve from entity id (works when client can provide it)
            if (traderEntityId >= 0) {
                try {
                    Entity e = player.level().getEntity(traderEntityId);
                    if (e instanceof Villager v) {
                        vill = v;
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: resolved villager via entityId={} uuid={}", traderEntityId, v.getUUID());
                    } else if (e != null) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: entityId={} is not Villager (type={})", traderEntityId, e.getClass().getName());
                    } else {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: no entity for entityId={}", traderEntityId);
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: entityId resolution failed (soft): {}", t.toString());
                }
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: traderEntityId < 0 (client could not resolve trader id)");
            }

            // 2) Fallback: resolve from open MerchantMenu trader (server-authoritative, matches reroll path)
            if (vill == null) {
                try {
                    if (player.containerMenu instanceof MerchantMenu menu) {
                        Merchant trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                        if (trader instanceof Villager v) {
                            vill = v;
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: resolved villager via MerchantMenu trader uuid={}", v.getUUID());
                        } else {
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: MerchantMenu trader is not Villager (traderType={})",
                                    trader == null ? "null" : trader.getClass().getName());
                        }
                    } else {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: player.containerMenu is not MerchantMenu (menuType={})",
                                player.containerMenu == null ? "null" : player.containerMenu.getClass().getName());
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: MerchantMenu fallback resolution failed (soft): {}", t.toString());
                }
            }

            int villLevel = vill != null ? vill.getVillagerData().getLevel() : 1;
            int villXp = vill != null ? vill.getVillagerXp() : 0;

            String costSpec = ServerConfig.costSpec == null ? "minecraft:emerald" : ServerConfig.costSpec;
            boolean preferWallet = ServerConfig.preferWallet;

            // ---- Offer-based cost computation (authoritative) ----
            int totalOffers = 0;
            long lockMask = 0L;
            int lockedCount = 0;

            if (vill != null) {
                totalOffers = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
                lockMask = TradeLockState.getMask(vill);
                long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
                if (sanitized != lockMask) {
                    TradeLockState.setMask(vill, sanitized);
                    lockMask = sanitized;
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: sanitized lock mask (villager={} offers={} newMask={})",
                            vill.getUUID(), totalOffers, Long.toUnsignedString(lockMask));
                }
                lockedCount = Long.bitCount(lockMask);
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipService: villager unresolved -> tooltip will show offers=0/cost=free");
            }

            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedCount, maxDeduct);

            int effectiveOffers = Math.max(0, totalOffers - deductibleLocks);

            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            int paidOffers = Math.max(0, effectiveOffers - freeOffers);

            long rawCost = (long) paidOffers * (long) costPerOffer;
            int cost;
            if (rawCost < 0) cost = 0;
            else if (rawCost > Integer.MAX_VALUE) cost = Integer.MAX_VALUE;
            else cost = (int) rawCost;

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] TooltipService cost calc: totalOffers={} lockedCount={} deductibleLocks={} effectiveOffers={} freeOffers={} paidOffers={} costPerOffer={} -> cost={}",
                    totalOffers, lockedCount, deductibleLocks, effectiveOffers, freeOffers, paidOffers, costPerOffer, cost
            );

            // Tooltip fields
            out.cost.itemOrTag = costSpec;
            out.cost.item = (!ServerConfig.isTagSpec(costSpec)) ? ResourceLocation.tryParse(costSpec) : null;
            out.cost.baseCost = cost;
            out.cost.scaledCost = cost;

            // "Next level" cost no longer exists in offer-based model
            out.cost.nextCostIfUsed = null;
            out.cost.maxCostPossible = null;

            // Breakdown
            out.cost.totalOffers = totalOffers;
            out.cost.lockedOffers = lockedCount;
            out.cost.deductibleLockedOffers = deductibleLocks;
            out.cost.freeOffers = freeOffers;
            out.cost.paidOffers = paidOffers;
            out.cost.costPerOffer = costPerOffer;

            out.villager.level = villLevel;
            out.villager.xp = villXp;

            // Afford check: wallet only for exact items
            if (cost <= 0) {
                out.afford.canAfford = true;
                out.afford.source = "none";
            } else {
                boolean walletOK = false;
                boolean invOK = false;

                if (preferWallet && out.cost.item != null && MoneyBridge.isLCPresent()) {
                    walletOK = MoneyBridge.canAfford(player, out.cost.item, cost);
                }

                if (out.cost.item != null) {
                    Item item = BuiltInRegistries.ITEM.get(out.cost.item);
                    if (item != null) {
                        int invCount = countInInventory(player, item);
                        invOK = invCount >= cost;
                    }
                } else {
                    invOK = false;
                }

                boolean can = walletOK || invOK;
                out.afford.canAfford = can;
                out.afford.source = can ? (walletOK && invOK ? "both" : (walletOK ? "wallet" : "inventory")) : "none";
            }

            // ----------------------------------------------------------
            // Daily cap + time-until-reset (ticksUntilReset)
            // ----------------------------------------------------------
            int cap = Math.max(0, ServerConfig.perVillagerDaily);
            boolean capEnabled = cap > 0;

            out.cap.enabled = capEnabled;
            out.cap.cap = cap;

            if (capEnabled) {
                // time until the NEXT reset at Minecraft midnight (18,000)
                out.cap.ticksUntilReset = ticksUntilNextMidnightReset(player);

                // remaining only if villager resolved
                if (vill != null) {
                    out.cap.remaining = RerollState.getDailyRemaining(player.serverLevel(), vill);
                } else {
                    out.cap.remaining = -1; // unknown (villager not resolved)
                }
            } else {
                out.cap.remaining = -1;
                out.cap.ticksUntilReset = -1;
            }

            out.cfg.version = ServerConfig.cfgVersion();
            out.cfg.hash = ServerConfig.cfgHash();
            out.cfg.preferWallet = preferWallet;
            out.cfg.freeMode = (cost <= 0);
            out.cfg.capEnabled = capEnabled;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] TooltipService.computeSnapshot failed", t);
        }
        return out;
    }

    /**
     * Compute ticks until the next reset at Minecraft midnight.
     *
     * Vanilla time:
     * - 0     = 06:00
     * - 6000  = 12:00
     * - 12000 = 18:00
     * - 18000 = 00:00 (midnight)  <-- reset moment
     *
     * Return value is in game ticks (20 ticks = 1 real second).
     * At the exact reset tick (mod==18000), returns 0.
     */
    private static int ticksUntilNextMidnightReset(ServerPlayer player) {
        try {
            if (player == null || player.serverLevel() == null) return -1;

            long dayTime = player.serverLevel().getDayTime();
            long mod = Math.floorMod(dayTime, 24000L);

            if (mod == 18000L) return 0;

            long until;
            if (mod < 18000L) {
                until = 18000L - mod;
            } else {
                until = (24000L - mod) + 18000L;
            }

            if (until < 0L) until = 0L;
            if (until > Integer.MAX_VALUE) until = Integer.MAX_VALUE;
            return (int) until;

        } catch (Throwable t) {
            return -1;
        }
    }

    private static int countInInventory(ServerPlayer player, Item item) {
        int total = 0;
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
            }
        } catch (Throwable ignored) {}
        return total;
    }

    private TooltipService() {}
}
