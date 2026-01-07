// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/mixin/VillagerDisableVanillaAutoTradeRefreshMixin.java
package org.z2six.ezvillagerreroll.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Disables vanilla's periodic "trade reroll" behavior for villagers that have never been traded with.
 *
 * Vanilla mechanic (as documented publicly): a villager with 0 XP can refresh/reset its trades "every so often"
 * until it has been traded with at least once, at which point offers become locked. We disable that automatic
 * refresh entirely so our own reroll + trade-lock system remains stable across days.
 *
 * Implementation strategy:
 * - Redirect the call to Villager#updateTrades() from Villager#tick (and aiStep as a fallback).
 * - If the villager is still a novice with 0 XP (i.e., never traded), we NO-OP the call.
 *
 * Notes:
 * - This is intentionally conservative: it only blocks updateTrades() when invoked from tick/aiStep and only
 *   for novice (level 1) villagers with 0 XP. It should not interfere with level-up trade generation paths.
 * - Uses defensive try/catch and debug logging to avoid crashing if mappings or callsites differ.
 */
@Mixin(Villager.class)
public class VillagerDisableVanillaAutoTradeRefreshMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_tick(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "tick");
    }

    @Redirect(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/Villager;updateTrades()V"),
            require = 0
    )
    private void ezvillagerreroll$disableAutoTradeRefresh_aiStep(Villager self) {
        ezvillagerreroll$maybeSkipUpdateTrades(self, "aiStep");
    }

    private static void ezvillagerreroll$maybeSkipUpdateTrades(Villager self, String caller) {
        // Never crash the game/server because of a trade-refresh prevention mixin.
        try {
            if (self == null) {
                LOGGER.warn("[EZVillagerReroll] Intercepted updateTrades() with null villager (caller={}); skipping.", caller);
                return;
            }

            // VillagerData holds profession + level.
            VillagerData data = self.getVillagerData();

            // XP is granted from trading; the vanilla "auto refresh" applies before first trade (xp=0).
            int xp = self.getVillagerXp();
            int level = data.getLevel();

            // Only block for novice villagers with 0 XP.
            if (level <= 1 && xp <= 0) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "[EZVillagerReroll] Skipping vanilla updateTrades() auto-refresh call from {} for villager id={} (level={}, xp={}).",
                            caller, self.getId(), level, xp
                    );
                }
                return; // NO-OP: disable vanilla auto trade refresh
            }

            // Otherwise, allow vanilla behavior.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "[EZVillagerReroll] Allowing vanilla updateTrades() call from {} for villager id={} (level={}, xp={}).",
                        caller, self.getId(), level, xp
                );
            }

            ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
        } catch (Throwable t) {
            // Fail open: if anything goes wrong, allow vanilla updateTrades() rather than risking broken villagers.
            int id = -1;
            try {
                id = (self != null) ? self.getId() : -1;
            } catch (Throwable ignored) {
                // ignore
            }

            LOGGER.warn(
                    "[EZVillagerReroll] Error while intercepting updateTrades() (caller={}). Falling back to vanilla behavior. Villager id={}.",
                    caller, id, t
            );

            try {
                if (self != null) {
                    ((VillagerUpdateTradesInvoker) self).ezvillagerreroll$invokeUpdateTrades();
                }
            } catch (Throwable t2) {
                // Last resort: swallow; better a logged issue than a crash loop.
                LOGGER.warn("[EZVillagerReroll] Fallback invoker call to updateTrades() also failed; skipping to avoid crash.", t2);
            }
        }
    }
}
