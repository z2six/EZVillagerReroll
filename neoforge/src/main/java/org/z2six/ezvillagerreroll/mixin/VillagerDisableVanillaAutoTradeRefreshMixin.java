// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/VillagerDisableVanillaAutoTradeRefreshMixin.java
package org.z2six.ezvillagerreroll.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.trading.MerchantOffers;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Robustly prevents vanilla from regenerating/resetting villager offers automatically.
 *
 * Problem summary:
 * - Vanilla can call Villager#updateTrades() from multiple code paths:
 *   - periodic AI/tick paths (your original mixin addressed this partially)
 *   - NBT load paths during server restart / chunk load (the missing piece)
 *
 * Critical constraint:
 * - EZVR still needs to be able to reroll trades by invoking updateTrades() manually via your invoker.
 * - Therefore, we do NOT cancel updateTrades() globally.
 * - Instead, we no-op ONLY when updateTrades() is invoked from vanilla "auto" call-sites.
 *
 * Strategy:
 * 1) Intercept updateTrades() call from readAdditionalSaveData:
 *    - This is the most common "restart -> trades changed" trigger.
 *    - We skip ONLY if offers already exist (saved NBT offers were loaded). If offers are empty, we allow vanilla.
 * 2) Intercept updateTrades() call from tick/aiStep/customServerAiStep:
 *    - We skip ONLY for novice villagers with 0 XP *and* non-empty offers (vanilla "refresh until traded" behavior).
 *    - If offers are empty, we allow vanilla to generate initial offers (safer fail-open behavior).
 *
 * Defensive behavior:
 * - Never crash: try/catch around all logic.
 * - Fail open: if anything goes wrong, call the invoker to run vanilla updateTrades().
 * - Debug logs for visibility, but minimal spam.
 */
@Mixin(Villager.class)
public class VillagerDisableVanillaAutoTradeRefreshMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    // -----------------------------------------
    // NBT LOAD PATH (server restart / chunk load)
    // -----------------------------------------

    @Redirect(
            method = "readAdditionalSaveData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_readAdditionalSaveData(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "readAdditionalSaveData", /*loadPath=*/true);
    }

    // -----------------------------------------
    // PERIODIC PATHS (daily/periodic/AI refresh)
    // -----------------------------------------

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_tick(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "tick", /*loadPath=*/false);
    }

    @Redirect(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_aiStep(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "aiStep", /*loadPath=*/false);
    }

    @Redirect(
            method = "customServerAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_customServerAiStep(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "customServerAiStep", /*loadPath=*/false);
    }

    // -----------------------------------------
    // Core logic
    // -----------------------------------------

    private static void ezvillagerreroll$maybeSkipUpdateTrades(Villager self, String caller, boolean loadPath) {
        try {
            if (self == null) {
                LOGGER.warn("[EZVR] Intercepted updateTrades() with null villager (caller={}); skipping to avoid crash.", caller);
                return;
            }

            final int id = safeGetId(self);

            // Offers state is the safest guard we can use:
            // - If offers are non-empty, we almost certainly loaded/persisted offers already
            //   and vanilla "refresh" would overwrite/reshuffle them.
            // - If offers are empty, allowing vanilla to generate prevents "no trades" edge cases.
            final MerchantOffers offers = safeGetOffers(self);
            final boolean hasOffers = offers != null && !offers.isEmpty();

            final VillagerData data = self.getVillagerData();
            final int level = (data != null) ? data.getLevel() : -1;
            final int xp = self.getVillagerXp();

            if (loadPath) {
                // Robust fix for "after restarting server, trades reset":
                // If offers were loaded from NBT (non-empty), do not allow vanilla to refresh them on load.
                if (hasOffers) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("[EZVR] Skipping vanilla updateTrades() on NBT load for villager id={} (level={}, xp={}, offers={}).",
                                id, level, xp, offers.size());
                    }
                    return; // NO-OP
                }

                // If offers are empty on load, fail open so villagers still get trades.
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[EZVR] Allowing updateTrades() on NBT load because offers are empty for villager id={} (level={}, xp={}).",
                            id, level, xp);
                }
                ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
                return;
            }

            // Periodic vanilla refresh behavior typically applies before first trade:
            // novice (level 1) with 0 XP; vanilla can keep refreshing.
            // We only skip if offers already exist, to avoid breaking initial offer generation.
            final boolean isNoviceNeverTraded = (level <= 1 && xp <= 0);

            if (isNoviceNeverTraded && hasOffers) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[EZVR] Skipping vanilla updateTrades() auto-refresh from {} for villager id={} (level={}, xp={}, offers={}).",
                            caller, id, level, xp, offers.size());
                }
                return; // NO-OP
            }

            // Otherwise, allow vanilla call site to proceed (via invoker).
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[EZVR] Allowing updateTrades() from {} for villager id={} (level={}, xp={}, offersPresent={}).",
                        caller, id, level, xp, hasOffers);
            }

            ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
        } catch (Throwable t) {
            // Fail open: do not risk broken villagers or crash loops.
            int id = -1;
            try {
                id = safeGetId(self);
            } catch (Throwable ignored) {
                // ignore
            }

            LOGGER.warn("[EZVR] Error while intercepting updateTrades() (caller={}, loadPath={}). Falling back to vanilla. Villager id={}.",
                    caller, loadPath, id, t);

            try {
                if (self != null) {
                    ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
                }
            } catch (Throwable t2) {
                LOGGER.warn("[EZVR] Fallback invoker call to updateTrades() also failed; skipping to avoid crash.", t2);
            }
        }
    }

    private static int safeGetId(Villager self) {
        try {
            return self.getId();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static MerchantOffers safeGetOffers(Villager self) {
        try {
            return self.getOffers();
        } catch (Throwable t) {
            return null;
        }
    }
}
