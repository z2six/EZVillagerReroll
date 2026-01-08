// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;
import org.z2six.ezvillagerreroll.logic.RerollState;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.server.CatalogBuilder;
import org.z2six.ezvillagerreroll.server.SearchService;

import java.util.List;

/**
 * Server-side packet handlers.
 */
public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            EZVillagerReroll.LOG().debug("[EZVR] handleReroll: start (player={})", sp.getGameProfile().getName());

            // Execute reroll logic (may succeed or fail; it internally enforces cooldown)
            RerollExecutor.tryReroll(sp);

            // Always refresh client view of cooldown after an attempt (success or refusal)
            try {
                sendCooldownStateSnapshot(sp, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Post-reroll cooldown snapshot failed (soft): {}", t.toString());
            }

            try {
                sendCurrentTradeLocksSnapshot(sp, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Post-reroll lock snapshot failed (soft): {}", t.toString());
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll failed", t);
        }
    }

    public static void handleRerollCooldownQuery(PacketRerollCooldownQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            EZVillagerReroll.LOG().debug("[EZVR] handleRerollCooldownQuery: player={}", sp.getGameProfile().getName());

            sendCooldownStateSnapshot(sp, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleRerollCooldownQuery failed", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            final int idx;
            try {
                idx = msg.tradeIndex();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ToggleTradeLock: cannot read tradeIndex() from PacketToggleTradeLock.", t);
                return;
            }

            if (idx < 0 || idx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock: invalid idx={} (player={})",
                        idx, sp.getGameProfile().getName());
                return;
            }

            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: player not in MerchantMenu (player={}, idx={})",
                        sp.getGameProfile().getName(), idx);
                return;
            }

            final int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: trader not Villager (player={}, idx={}, trader={})",
                        sp.getGameProfile().getName(), idx, trader == null ? "null" : trader.getClass().getName());

                safeReply(ctx, new PacketTradeLocks(containerId, 0L));
                return;
            }

            long next = TradeLockState.toggle(vill, idx);
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(next, offerSize);
            if (sanitized != next) {
                TradeLockState.setMask(vill, sanitized);
                next = sanitized;
            }

            EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: OK (player={}, villager={}, idx={}, mask={}, containerId={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    idx,
                    Long.toUnsignedString(next),
                    containerId
            );

            safeReply(ctx, new PacketTradeLocks(containerId, next));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock failed", t);
        }
    }

    public static void handleSearchCatalogQuery(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] handleSearchCatalogQuery: could not resolve villager");
                ctx.reply(PacketSearchCatalogData.minimal(msg.villagerEntityId(), List.of()));
                return;
            }

            List<net.minecraft.world.item.ItemStack> items = CatalogBuilder.buildCatalog(vill);

            // Compute the hourly cost preview (server-authoritative).
            int offerCount = 0;
            try { offerCount = (vill.getOffers() == null) ? 0 : Math.max(0, vill.getOffers().size()); } catch (Throwable ignored) {}

            long mask = 0L;
            try { mask = TradeLockState.getMask(vill); } catch (Throwable ignored) {}

            int lockedCount = countLockedOffers(mask, offerCount);

            int deductibleLocked = Math.min(Math.max(0, lockedCount), Math.max(0, ServerConfig.maxDeductibleLockedOffers));
            int effectivePaidOffers = Math.max(0, offerCount - Math.max(0, ServerConfig.freeOffers) - deductibleLocked);
            long manualCostLong = (long) effectivePaidOffers * (long) Math.max(0, ServerConfig.costPerOffer);
            int manualCost = clampToInt(manualCostLong);

            int cooldown = Math.max(0, ServerConfig.cooldownTicks);
            int rerollsPerHour = (cooldown <= 0) ? 0 : (1000 / cooldown);

            long hourlyBase = (long) manualCost * (long) rerollsPerHour;

            int threshold = Math.max(0, ServerConfig.autoHourlyThreshold);
            double pct = Math.max(0.0, ServerConfig.autoHourlyDiscountOrIncreasePct) / 100.0;

            long hourlyFinal = applyThresholdScaling(hourlyBase, effectivePaidOffers, threshold, pct);
            int hourlyCost = clampToInt(hourlyFinal);

            EZVillagerReroll.LOG().debug(
                    "[EZVR] CatalogQuery hourly preview: villagerId={} offerCount={} lockedCount={} deductibleLocked={} effectivePaidOffers={} manualCost={} cooldown={} rerollsPerHour={} hourlyBase={} threshold={} pct={} hourlyFinal={}",
                    vill.getId(), offerCount, lockedCount, deductibleLocked, effectivePaidOffers, manualCost, cooldown, rerollsPerHour, hourlyBase, threshold, pct, hourlyFinal
            );

            ctx.reply(new PacketSearchCatalogData(vill.getId(), items, offerCount, lockedCount, effectivePaidOffers, manualCost, hourlyCost));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleSearchCatalogQuery failed", t);
        }
    }

    public static void handleStartAutoSearch(PacketStartAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] handleStartAutoSearch: could not resolve villager");
                return;
            }

            SearchService.start(sp, vill, msg.targets());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleStartAutoSearch failed", t);
        }
    }

    public static void handleCancelAutoSearch(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            SearchService.cancelByEntityId(sp, msg.villagerEntityId());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleCancelAutoSearch failed", t);
        }
    }

    public static void handleContinueAutoSearch(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // No state change required; reroll continues. Log for observability.
            EZVillagerReroll.LOG().debug("[EZVR] ContinueAutoSearch received (player={} villagerEntityId={})",
                    sp.getGameProfile().getName(), msg.villagerEntityId());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleContinueAutoSearch failed", t);
        }
    }

    private static Villager resolveVillagerFor(ServerPlayer sp, int villagerEntityId) {
        try {
            if (villagerEntityId >= 0) {
                ServerLevel lvl = sp.serverLevel();
                Entity e = lvl.getEntity(villagerEntityId);
                if (e instanceof Villager v) return v;
            }

            if (sp.containerMenu instanceof MerchantMenu menu) {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Villager v) return v;
            }

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countLockedOffers(long mask, int offerCount) {
        try {
            if (offerCount <= 0) return 0;
            int n = Math.min(63, offerCount);
            int c = 0;
            for (int i = 0; i < n; i++) {
                long bit = 1L << i;
                if ((mask & bit) != 0L) c++;
            }
            return Math.max(0, c);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long applyThresholdScaling(long hourlyBase, int effectivePaidOffers, int threshold, double pctPerStep) {
        try {
            if (hourlyBase <= 0) return 0L;
            if (pctPerStep <= 0.0) return hourlyBase;

            int paid = Math.max(0, effectivePaidOffers);
            int th = Math.max(0, threshold);

            int delta = th - paid;
            if (delta == 0) return hourlyBase;

            double factor;
            if (delta > 0) {
                // Below threshold -> price increase per missing paid offer.
                factor = 1.0 + (pctPerStep * (double) delta);
            } else {
                // Above threshold -> discount per extra paid offer.
                factor = 1.0 - (pctPerStep * (double) (-delta));
                if (factor < 0.0) factor = 0.0;
            }

            double scaled = (double) hourlyBase * factor;
            if (scaled <= 0.0) return 0L;

            // Round to nearest long. (If you prefer ceil, switch to Math.ceil.)
            long out = Math.round(scaled);
            if (out < 0L) out = 0L;
            return out;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] applyThresholdScaling failed (soft): {}", t.toString());
            return Math.max(0L, hourlyBase);
        }
    }

    private static int clampToInt(long v) {
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }

    private static void sendCurrentTradeLocksSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                safeReply(ctx, new PacketTradeLocks(containerId, 0L));
                return;
            }

            long mask = TradeLockState.getMask(vill);
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, offerSize);
            if (sanitized != mask) {
                TradeLockState.setMask(vill, sanitized);
                mask = sanitized;
            }

            safeReply(ctx, new PacketTradeLocks(containerId, mask));
        } catch (Throwable ignored) {}
    }

    private static void sendCooldownStateSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                // No villager -> treat as "not cooling down"
                safeReplyCooldown(ctx, new PacketRerollCooldownState(containerId, 0, Math.max(0, ServerConfig.cooldownTicks)));
                return;
            }

            int remaining = RerollState.cooldownRemainingTicks(sp.serverLevel(), vill);
            int cfg = Math.max(0, ServerConfig.cooldownTicks);

            safeReplyCooldown(ctx, new PacketRerollCooldownState(containerId, remaining, cfg));

            EZVillagerReroll.LOG().debug(
                    "[EZVR] Cooldown snapshot: player={} containerId={} villager={} remainingTicks={} cfgCooldownTicks={}",
                    sp.getGameProfile().getName(), containerId, vill.getUUID(), remaining, cfg
            );
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] sendCooldownStateSnapshot failed (soft): {}", t.toString());
        }
    }

    private static void safeReply(IPayloadContext ctx, PacketTradeLocks msg) {
        try {
            ctx.reply(msg);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] safeReply(PacketTradeLocks) failed (soft): {}", t.toString());
        }
    }

    private static void safeReplyCooldown(IPayloadContext ctx, PacketRerollCooldownState msg) {
        try {
            ctx.reply(msg);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] safeReply(PacketRerollCooldownState) failed (soft): {}", t.toString());
        }
    }
}
