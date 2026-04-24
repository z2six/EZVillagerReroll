// neoforge\src\main\java\org\z2six\villageroverhaul\logic\RerollExecutor.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.logic.HoarderOffers;
import org.z2six.villageroverhaul.server.VillagerGenerosityOfferService;

import java.lang.reflect.Method;

public final class RerollExecutor {

    public static void tryReroll(ServerPlayer sp) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] tryReroll: player not in MerchantMenu; ignoring (player={})", sp.getGameProfile().getName());
                return;
            }

            if (menu.getSlot(2).hasItem()) {
                toast(sp, "ezvr.msg.mid_trade");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Reroll refused: result slot occupied (player={})", sp.getGameProfile().getName());
                return;
            }

            Merchant trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                toast(sp, "ezvr.msg.not_villager");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Reroll refused: trader is not a Villager (player={}, traderType={})",
                        sp.getGameProfile().getName(),
                        trader == null ? "null" : trader.getClass().getName()
                );
                return;
            }

            try { org.z2six.villageroverhaul.server.VillagerStatsService.ensureStats(vill); } catch (Throwable ignored) {}

            int level = Math.max(1, Math.min(5, vill.getVillagerData().getLevel()));
            int xp = vill.getVillagerXp();

            int offersBefore = vill.getOffers() != null ? vill.getOffers().size() : -1;

            if (!RerollState.canReroll(sp, vill)) {
                toast(sp, "ezvr.msg.cooldown_or_cap");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Reroll refused: cooldown/cap (player={}, villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            if (!ServerConfig.allowAfterTradeUsed && xp > 0) {
                toast(sp, "ezvr.msg.after_used_disabled");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Reroll refused: allowAfterTradeUsed=false (player={}, villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            int totalOffers = Math.max(0, offersBefore);
            long lockMask = TradeLockState.getMask(vill);

            long beforeMaskForLog = lockMask;
            long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
            if (sanitized != lockMask) {
                TradeLockState.setMask(vill, sanitized);
                lockMask = sanitized;

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Sanitized lock mask during reroll cost calc (villager={} beforeMask={} afterMask={})",
                        vill.getUUID(), Long.toUnsignedString(beforeMaskForLog), Long.toUnsignedString(sanitized));
            }

            int lockedCount = Long.bitCount(lockMask);

            final int offersRerolled = Math.max(0, totalOffers - lockedCount);
            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            // Cost is based ONLY on offers that are actually rerolled (unlocked indices).
            int paidOffers = Math.max(0, offersRerolled - freeOffers);

            int baseCost;
            try {
                long c = (long) paidOffers * (long) costPerOffer;
                if (c < 0) c = 0;
                if (c > Integer.MAX_VALUE) c = Integer.MAX_VALUE;
                baseCost = (int) c;
            } catch (Throwable t) {
                baseCost = Integer.MAX_VALUE;
            }

            double generosityPct = 0.0;
            int cost = baseCost;
            try {
                generosityPct = VillagerTraitEffects.generosityPct(vill);
                cost = VillagerTraitEffects.applyCostPercent(baseCost, generosityPct);
            } catch (Throwable ignored) {
                cost = baseCost;
                generosityPct = 0.0;
            }

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Reroll attempt: player={}, villager={}, prof={}, level={}, xp={}, offersBefore={}, lockedCount={}, offersRerolled(unlocked)={}, freeOffers={}, paidOffers={}, costPerOffer={}, baseCost={}, generosityPct={}, cost={}, costSpec='{}' (preferWallet={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getVillagerData().getProfession(),
                    level, xp, offersBefore,
                    lockedCount, offersRerolled,
                    freeOffers, paidOffers, costPerOffer,
                    baseCost, generosityPct, cost,
                    ServerConfig.costSpec, ServerConfig.preferWallet
            );

            boolean paid = false;

            if (cost > 0) {
                paid = PaymentUtil.tryCharge(sp, cost);
                if (!paid) {
                    toast(sp, "ezvr.msg.not_enough");
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Reroll refused: insufficient funds for {} x {}", cost, ServerConfig.costSpec);
                    return;
                }
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Cost paid via PaymentUtil: {} x {}", cost, ServerConfig.costSpec);
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Cost is zero (free reroll per offer-based config).");
                paid = true;
            }

            if (!TradeUtil.rebuildOffers(vill, sp)) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] Reroll failed during rebuild (villager={})", vill.getUUID());
                toast(sp, "ezvr.msg.failed");
                return;
            }

            // ✅ This IS a rebuild, so reset baseline/applied then enforce Hoarder.
            try {
                HoarderOffers.normalizeAfterOfferRebuild(vill, sp);
                VillagerGenerosityOfferService.normalizeAndApply(vill);
            } catch (Throwable ignored) {}

            // XP + potential level-up (which now re-normalizes inside grantVillagerXpForManualReroll)
            grantVillagerXpForManualReroll(sp, menu, vill, offersRerolled);

            int offersAfter = vill.getOffers() != null ? vill.getOffers().size() : -1;
            RerollState.markRerolled(sp, vill);
            toast(sp, "ezvr.msg.success");
            try { org.z2six.villageroverhaul.server.VillagerHistoryService.addManualReroll(vill, 1); } catch (Throwable ignored) {}
            try {
                // Track emeralds earned from manual rerolls (only when the costSpec is exactly emerald).
                if (cost > 0) {
                    boolean specIsTag = ServerConfig.isTagSpec(ServerConfig.costSpec);
                    ResourceLocation itemId = specIsTag ? null : ResourceLocation.tryParse(ServerConfig.costSpec);
                    if (!specIsTag && itemId != null && "minecraft:emerald".equals(itemId.toString())) {
                        org.z2six.villageroverhaul.server.VillagerHistoryService.addEmeraldsFromManualRerolls(vill, cost);
                    }
                }
            } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Reroll success: villager={}, offers {} -> {}, player={}, paid={}",
                    vill.getUUID(), offersBefore, offersAfter, sp.getGameProfile().getName(),
                    (cost <= 0) ? "free" : (paid ? "yes" : "no")
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] tryReroll exception", t);
        }
    }

    private static void grantVillagerXpForManualReroll(ServerPlayer sp, MerchantMenu menu, Villager vill, int offersRerolled) {
        try {
            if (sp == null || menu == null || vill == null) return;

            double perOffer = Math.max(0.0, ServerConfig.manualRerollXpPerOffer);
            if (perOffer <= 0.0) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Manual reroll XP disabled (manualRerollXpPerOffer<=0).");
                return;
            }

            int rerolled = Math.max(0, offersRerolled);
            if (rerolled <= 0) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Manual reroll XP skipped: offersRerolled={} (nothing to reward).", offersRerolled);
                return;
            }

            double iPct = 0.0;
            double baseRaw = perOffer * (double) rerolled;

            int add;
            try {
                iPct = VillagerTraitEffects.intellectPct(vill);
                add = VillagerTraitEffects.applyXpPercentsRounded(baseRaw, iPct);
            } catch (Throwable ignored) {
                long addLong = Math.round(baseRaw);
                if (addLong < 0L) addLong = 0L;
                if (addLong > Integer.MAX_VALUE) addLong = Integer.MAX_VALUE;
                add = (int) addLong;
            }

            if (add <= 0) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Manual reroll XP rounded to 0 (perOffer={} rerolled={} baseRaw={} intellectPct={}).",
                        perOffer, rerolled, baseRaw, iPct);
                return;
            }

            int xpBefore = 0;
            int lvlBefore = 0;
            try { xpBefore = vill.getVillagerXp(); } catch (Throwable ignored) {}
            try { lvlBefore = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}

            boolean applied = addVillagerXpSafe(vill, add);
            if (!applied) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] Manual reroll XP: failed to apply villager XP (add={}, perOffer={}, offersRerolled={}, villager={})",
                        add, perOffer, rerolled, vill.getUUID());
                return;
            }

            boolean scheduledVanillaLevelUp = maybeInvokeVanillaLevelUpFlow(vill);

            // Immediate normalize (best effort)
            try { HoarderOffers.normalizeOffers(vill, sp); } catch (Throwable ignored) {}
            try { VillagerGenerosityOfferService.normalizeAndApply(vill); } catch (Throwable ignored) {}

            int xpAfter = xpBefore;
            int lvlAfter = lvlBefore;
            try { xpAfter = vill.getVillagerXp(); } catch (Throwable ignored) {}
            try { lvlAfter = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}

            // Immediate GUI refresh
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
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Manual reroll XP: sendMerchantOffers refresh failed (soft): {}", t.toString());
            }

            // CRITICAL: vanilla may still append/refresh offers AFTER this call stack.
            // Next tick we re-apply Hoarder+Generosity and re-sync if still open.
            scheduleNextTickOfferRecheck(vill, sp, menu);

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Manual reroll XP granted: villager={} offersRerolled={} perOffer={} baseRaw={} intellectPct={} add={} scheduledVanillaLevelUp={} xp {}->{} level {}->{} offersNow={}",
                    vill.getUUID(), rerolled, perOffer, baseRaw, iPct, add, scheduledVanillaLevelUp,
                    xpBefore, xpAfter, lvlBefore, lvlAfter,
                    (vill.getOffers() == null ? -1 : vill.getOffers().size())
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] grantVillagerXpForManualReroll failed (soft)", t);
        }
    }

    /**
     * Vanilla-accurate level-up trigger:
     * - call Villager.shouldIncreaseLevel() (private)
     * - if true, call Villager.increaseMerchantCareer() (private)
     *
     * No hardcoded XP thresholds.
     */
    private static boolean maybeInvokeVanillaLevelUpFlow(Villager vill) {
        try {
            if (vill == null) return false;

            // If already maxed, don't poke.
            int lvl = 0;
            try { lvl = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            if (lvl >= 5) return false;

            // Mojmap name: shouldIncreaseLevel()
            // (mappings.dev shows it exists and is private) :contentReference[oaicite:1]{index=1}
            boolean should = tryInvokeBooleanNoArgMethodAnyVisibility(vill, "shouldIncreaseLevel");

            // extra fallback aliases across mappings/mod-envs (harmless if missing)
            if (!should) should = tryInvokeBooleanNoArgMethodAnyVisibility(vill, "canLevelUp");
            if (!should) return false;

            // Mojmap name: increaseMerchantCareer()
            // (mappings.dev shows it exists and is private) :contentReference[oaicite:2]{index=2}
            if (tryInvokeNoArgMethodAnyVisibility(vill, "increaseMerchantCareer")) return true;

            // extra fallbacks across mappings
            if (tryInvokeNoArgMethodAnyVisibility(vill, "levelUp")) return true;
            if (tryInvokeNoArgMethodAnyVisibility(vill, "increaseProfessionLevel")) return true;

            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] maybeInvokeVanillaLevelUpFlow failed (soft): {}", t.toString());
            return false;
        }
    }

    private static boolean tryInvokeBooleanNoArgMethodAnyVisibility(Object target, String name) {
        try {
            if (target == null || name == null) return false;

            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    Object r = m.invoke(target);
                    return (r instanceof Boolean b) && b;
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method m2 = c.getMethod(name);
                        Object r2 = m2.invoke(target);
                        return (r2 instanceof Boolean b2) && b2;
                    } catch (NoSuchMethodException ignored2) {
                        // keep walking
                    }
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tryInvokeBooleanNoArgMethodAnyVisibility failed name={} err={}", name, t.toString());
            return false;
        }
    }

    private static boolean tryInvokeNoArgMethodAnyVisibility(Object target, String name) {
        try {
            if (target == null || name == null) return false;

            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    m.invoke(target);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method m2 = c.getMethod(name);
                        m2.invoke(target);
                        return true;
                    } catch (NoSuchMethodException ignored2) {
                        // keep walking
                    }
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tryInvokeNoArgMethodAnyVisibility failed name={} err={}", name, t.toString());
            return false;
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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] addVillagerXpSafe failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void toast(ServerPlayer sp, String key) {
        try {
            sp.displayClientMessage(Component.translatable(key), true);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] toast failed for key={}: {}", key, t.toString());
        }
    }

    private static void scheduleNextTickOfferRecheck(Villager vill, ServerPlayer sp, MerchantMenu menu) {
        try {
            if (vill == null) return;

            final net.minecraft.server.MinecraftServer srv;
            try {
                srv = vill.getServer();
            } catch (Throwable ignored) {
                return;
            }
            if (srv == null) return;

            final java.util.UUID villUuid;
            try {
                villUuid = vill.getUUID();
            } catch (Throwable ignored) {
                return;
            }

            final java.util.UUID playerUuid = (sp == null ? null : sp.getUUID());

            srv.execute(() -> {
                try {
                    // Resolve villager next tick (entity reference may be stale)
                    Villager v = null;
                    for (var lvl : srv.getAllLevels()) {
                        var ent = lvl.getEntity(villUuid);
                        if (ent instanceof Villager vv) { v = vv; break; }
                    }
                    if (v == null) return;

                    ServerPlayer p = (playerUuid == null) ? null : srv.getPlayerList().getPlayer(playerUuid);

                    // Re-apply Hoarder after vanilla has appended/updated offers.
                    try { HoarderOffers.normalizeOffers(v, p); } catch (Throwable ignored) {}

                    // Re-apply Generosity after vanilla has appended/updated offers.
                    try { org.z2six.villageroverhaul.server.VillagerGenerosityOfferService.normalizeAndApply(v); } catch (Throwable ignored) {}

                    // If the player is still trading THIS villager, resync the GUI.
                    if (p != null && p.containerMenu instanceof MerchantMenu mm) {
                        try {
                            net.minecraft.world.item.trading.Merchant trader =
                                    ((org.z2six.villageroverhaul.mixin.MerchantMenuAccessor) mm).ezvr$getTrader();
                            if (trader == v) {
                                p.sendMerchantOffers(
                                        mm.containerId,
                                        v.getOffers(),
                                        v.getVillagerData().getLevel(),
                                        v.getVillagerXp(),
                                        v.showProgressBar(),
                                        v.canRestock()
                                );
                            }
                        } catch (Throwable ignored) {}
                    }

                } catch (Throwable ignored) {}
            });

        } catch (Throwable ignored) {}
    }

    private RerollExecutor() {}
}
