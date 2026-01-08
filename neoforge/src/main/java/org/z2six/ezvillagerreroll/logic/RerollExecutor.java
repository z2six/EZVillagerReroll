// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/RerollExecutor.java
package org.z2six.ezvillagerreroll.logic;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.server.VillagerOffersSavedData;

import java.lang.reflect.Method;

public final class RerollExecutor {

    public static void tryReroll(ServerPlayer sp) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().debug("[EZVR] tryReroll: player not in MerchantMenu; ignoring (player={})", sp.getGameProfile().getName());
                return;
            }

            if (menu.getSlot(2).hasItem()) {
                toast(sp, "ezvr.msg.mid_trade");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: result slot occupied (player={})", sp.getGameProfile().getName());
                return;
            }

            Merchant trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                toast(sp, "ezvr.msg.not_villager");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: trader is not a Villager (player={}, traderType={})",
                        sp.getGameProfile().getName(),
                        trader == null ? "null" : trader.getClass().getName()
                );
                return;
            }

            int level = Math.max(1, Math.min(5, vill.getVillagerData().getLevel()));
            int xp = vill.getVillagerXp();

            int offersBefore = vill.getOffers() != null ? vill.getOffers().size() : -1;

            if (!RerollState.canReroll(sp, vill)) {
                toast(sp, "ezvr.msg.cooldown_or_cap");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: cooldown/cap (player={}, villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            if (!ServerConfig.allowAfterTradeUsed && xp > 0) {
                toast(sp, "ezvr.msg.after_used_disabled");
                EZVillagerReroll.LOG().info("[EZVR] Reroll refused: allowAfterTradeUsed=false (player={}, villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            // ---- offer-based cost + also defines "offers rerolled" count for XP ----
            int totalOffers = Math.max(0, offersBefore);
            long lockMask = TradeLockState.getMask(vill);

            long beforeMaskForLog = lockMask;
            long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
            if (sanitized != lockMask) {
                TradeLockState.setMask(vill, sanitized);
                lockMask = sanitized;

                EZVillagerReroll.LOG().debug("[EZVR] Sanitized lock mask during reroll cost calc (villager={} beforeMask={} afterMask={})",
                        vill.getUUID(), Long.toUnsignedString(beforeMaskForLog), Long.toUnsignedString(sanitized));
            }

            int lockedCount = Long.bitCount(lockMask);

            // This is the multiplier target for manual reroll XP:
            // only offers that are NOT locked are actually rerolled.
            final int offersRerolled = Math.max(0, totalOffers - lockedCount);

            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedCount, maxDeduct);

            int effectiveOffers = Math.max(0, totalOffers - deductibleLocks);
            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            int paidOffers = Math.max(0, effectiveOffers - freeOffers);

            int cost;
            try {
                long c = (long) paidOffers * (long) costPerOffer;
                if (c < 0) c = 0;
                if (c > Integer.MAX_VALUE) c = Integer.MAX_VALUE;
                cost = (int) c;
            } catch (Throwable t) {
                cost = Integer.MAX_VALUE;
            }

            EZVillagerReroll.LOG().info(
                    "[EZVR] Reroll attempt: player={}, villager={}, prof={}, level={}, xp={}, offersBefore={}, lockedCount={}, offersRerolled={}, deductibleLocks={}, effectiveOffers={}, freeOffers={}, paidOffers={}, costPerOffer={}, cost={}, costSpec='{}' (preferWallet={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getVillagerData().getProfession(),
                    level, xp, offersBefore,
                    lockedCount, offersRerolled,
                    deductibleLocks, effectiveOffers,
                    freeOffers, paidOffers, costPerOffer,
                    cost, ServerConfig.costSpec, ServerConfig.preferWallet
            );

            boolean paid = false;

            if (cost > 0) {
                boolean specIsTag = ServerConfig.isTagSpec(ServerConfig.costSpec);
                ResourceLocation itemId = specIsTag ? null : ResourceLocation.tryParse(ServerConfig.costSpec);
                boolean isExactItem = itemId != null && !specIsTag;

                // Wallet paths only supported for exact item specs (not tags)
                if (ServerConfig.preferWallet && isExactItem && MoneyBridge.isLCPresent()) {
                    boolean apiPaid = MoneyBridge.tryExtract(sp, itemId, cost);
                    if (apiPaid) {
                        EZVillagerReroll.LOG().info("[EZVR] Cost paid via LC MoneyAPI: {} x {}", cost, itemId);
                        paid = true;
                    } else {
                        EZVillagerReroll.LOG().info("[EZVR] LC MoneyAPI extraction failed; trying direct wallet next: {} x {}", cost, itemId);
                    }
                }

                if (!paid && ServerConfig.preferWallet && isExactItem && WalletBridge.isLCPresent()) {
                    boolean walletPaid = WalletBridge.tryWithdrawFromWallet(sp, itemId, cost);
                    if (walletPaid) {
                        EZVillagerReroll.LOG().info("[EZVR] Cost paid via LC wallet (direct): {} x {}", cost, itemId);
                        paid = true;
                    } else {
                        EZVillagerReroll.LOG().info("[EZVR] LC direct wallet failed; falling back to inventory: {} x {}", cost, itemId);
                    }
                }

                if (!paid) {
                    Ingredient ing = CostUtil.parseIngredient(ServerConfig.costSpec);
                    if (ing == Ingredient.EMPTY || !CostUtil.consume(sp, ing, cost)) {
                        toast(sp, "ezvr.msg.not_enough");
                        EZVillagerReroll.LOG().info("[EZVR] Reroll refused: insufficient inventory for {} x {}", cost, ServerConfig.costSpec);
                        return;
                    }
                    EZVillagerReroll.LOG().info("[EZVR] Cost consumed from inventory: {} x {}", cost, ServerConfig.costSpec);
                    paid = true;
                }
            } else {
                EZVillagerReroll.LOG().info("[EZVR] Cost is zero (free reroll per offer-based config).");
                paid = true;
            }

            if (!TradeUtil.rebuildOffers(vill, sp)) {
                EZVillagerReroll.LOG().warn("[EZVR] Reroll failed during rebuild (villager={})", vill.getUUID());
                toast(sp, "ezvr.msg.failed");
                return;
            }

            // NEW: Grant villager XP for successful manual reroll.
            // We do this AFTER rebuild succeeded, but BEFORE capturing canonical offers
            // so the persisted "canonical" offers reflect any immediate changes from XP gain.
            grantVillagerXpForManualReroll(sp, menu, vill, offersRerolled);

            // MANUAL REROLL RULE: After a successful manual reroll, store the new offers as canonical.
            try {
                VillagerOffersSavedData sd = VillagerOffersSavedData.get(sp.serverLevel());
                if (sd != null) {
                    sd.capture(vill);
                    EZVillagerReroll.LOG().debug("[EZVR] Manual reroll: canonical offers captured (villager={}, offers={})",
                            vill.getUUID(), vill.getOffers() == null ? -1 : vill.getOffers().size());
                } else {
                    EZVillagerReroll.LOG().warn("[EZVR] Manual reroll: VillagerOffersSavedData was null; canonical offers NOT captured (villager={})",
                            vill.getUUID());
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] Manual reroll: failed to capture canonical offers (soft) (villager={})", vill.getUUID(), t);
            }

            int offersAfter = vill.getOffers() != null ? vill.getOffers().size() : -1;
            RerollState.markRerolled(sp, vill);
            toast(sp, "ezvr.msg.success");

            EZVillagerReroll.LOG().info(
                    "[EZVR] Reroll success: villager={}, offers {} -> {}, player={}, paid={}",
                    vill.getUUID(), offersBefore, offersAfter, sp.getGameProfile().getName(),
                    (cost <= 0) ? "free" : (paid ? "yes" : "no")
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] tryReroll exception", t);
        }
    }

    private static void grantVillagerXpForManualReroll(ServerPlayer sp, MerchantMenu menu, Villager vill, int offersRerolled) {
        try {
            if (sp == null || menu == null || vill == null) return;

            int perOffer = Math.max(0, ServerConfig.manualRerollXpPerOffer);
            if (perOffer <= 0) {
                EZVillagerReroll.LOG().debug("[EZVR] Manual reroll XP disabled (manualRerollXpPerOffer=0).");
                return;
            }

            int rerolled = Math.max(0, offersRerolled);
            if (rerolled <= 0) {
                EZVillagerReroll.LOG().debug("[EZVR] Manual reroll XP skipped: offersRerolled={} (nothing to reward).", offersRerolled);
                return;
            }

            long addLong = (long) perOffer * (long) rerolled;
            if (addLong < 0L) addLong = 0L;
            if (addLong > Integer.MAX_VALUE) addLong = Integer.MAX_VALUE;
            int add = (int) addLong;

            int xpBefore = 0;
            int lvlBefore = 0;
            try { xpBefore = vill.getVillagerXp(); } catch (Throwable ignored) {}
            try { lvlBefore = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}

            boolean applied = addVillagerXpSafe(vill, add);

            int xpAfter = xpBefore;
            int lvlAfter = lvlBefore;
            try { xpAfter = vill.getVillagerXp(); } catch (Throwable ignored) {}
            try { lvlAfter = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}

            if (!applied) {
                EZVillagerReroll.LOG().warn("[EZVR] Manual reroll XP: failed to apply villager XP (add={}, perOffer={}, offersRerolled={}, villager={})",
                        add, perOffer, rerolled, vill.getUUID());
                return;
            }

            // Re-sync merchant offers to update XP/progress bar on the client immediately.
            try {
                sp.sendMerchantOffers(
                        menu.containerId,
                        vill.getOffers(),
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Manual reroll XP: sendMerchantOffers refresh failed (soft): {}", t.toString());
            }

            EZVillagerReroll.LOG().info(
                    "[EZVR] Manual reroll XP granted: villager={} offersRerolled={} perOffer={} add={} xp {}->{} level {}->{}",
                    vill.getUUID(), rerolled, perOffer, add, xpBefore, xpAfter, lvlBefore, lvlAfter
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] grantVillagerXpForManualReroll failed (soft)", t);
        }
    }

    /**
     * Try multiple method names across mappings/versions without crashing:
     * - addVillagerXp(int)
     * - addXp(int)
     * - setVillagerXp(getVillagerXp()+x)
     */
    private static boolean addVillagerXpSafe(Villager vill, int add) {
        try {
            if (vill == null) return false;
            if (add <= 0) return true; // treat "no-op" as success

            // 1) Mojmap-style
            try {
                Method m = vill.getClass().getMethod("addVillagerXp", int.class);
                m.invoke(vill, add);
                return true;
            } catch (Throwable ignored) {}

            // 2) Alternate
            try {
                Method m = vill.getClass().getMethod("addXp", int.class);
                m.invoke(vill, add);
                return true;
            } catch (Throwable ignored) {}

            // 3) Fallback: setVillagerXp(current+add)
            try {
                int cur = 0;
                try { cur = vill.getVillagerXp(); } catch (Throwable ignored2) { cur = 0; }

                long next = (long) cur + (long) add;
                if (next < 0L) next = 0L;
                if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;

                Method m = vill.getClass().getMethod("setVillagerXp", int.class);
                m.invoke(vill, (int) next);
                return true;
            } catch (Throwable ignored) {}

            return false;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] addVillagerXpSafe failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void toast(ServerPlayer sp, String key) {
        try {
            sp.displayClientMessage(Component.translatable(key), true);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] toast failed for key={}: {}", key, t.toString());
        }
    }

    private RerollExecutor() {}
}
