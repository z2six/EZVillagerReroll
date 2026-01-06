// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;

/**
 * Server-side packet handlers.
 *
 * IMPORTANT:
 * - Trade locking is keyed by the CURRENT open MerchantMenu (containerId),
 *   not by entityId. The client may not reliably know trader entity ids.
 * - We always operate on sp.containerMenu (must be MerchantMenu) to ensure
 *   we are toggling/preserving locks for the same villager used by reroll.
 *
 * NOTE ABOUT PacketToggleTradeLock:
 * - Java is statically compiled. We cannot “try” different accessor method names.
 * - This handler assumes your record accessor is msg.tradeIndex().
 *   (Which matches your earlier code / errors.)
 */
public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            EZVillagerReroll.LOG().debug("[EZVR] handleReroll: start (player={})", sp.getGameProfile().getName());

            // Perform reroll (TradeUtil.rebuildOffers(...) is responsible for preserving locked offers).
            RerollExecutor.tryReroll(sp);

            // After reroll, re-send lock mask snapshot so client overlay stays accurate.
            // Safe to do even if unchanged.
            try {
                sendCurrentTradeLocksSnapshot(sp, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Post-reroll lock snapshot failed (soft): {}", t.toString());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll failed", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            // IMPORTANT: must match your PacketToggleTradeLock record component name.
            // If your record is: record PacketToggleTradeLock(int tradeIndex) ...
            // then accessor is tradeIndex().
            final int idx;
            try {
                idx = msg.tradeIndex();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ToggleTradeLock: cannot read tradeIndex() from PacketToggleTradeLock. " +
                        "Your record accessor name does not match. Fix PacketToggleTradeLock or this handler.", t);
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

                // Keep client cache sane for this menu.
                safeReply(ctx, new PacketTradeLocks(containerId, 0L));
                return;
            }

            long next = TradeLockState.toggle(vill, idx);

            // Sanitize against current offer size.
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

            // Reply to update the client overlay immediately (keyed by containerId).
            safeReply(ctx, new PacketTradeLocks(containerId, next));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock failed", t);
        }
    }

    private static void sendCurrentTradeLocksSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().debug("[EZVR] sendCurrentTradeLocksSnapshot: not in MerchantMenu (player={})",
                        sp.getGameProfile().getName());
                return;
            }

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                EZVillagerReroll.LOG().debug("[EZVR] sendCurrentTradeLocksSnapshot: trader not Villager (player={}, trader={})",
                        sp.getGameProfile().getName(), trader == null ? "null" : trader.getClass().getName());
                safeReply(ctx, new PacketTradeLocks(containerId, 0L));
                return;
            }

            long mask = TradeLockState.getMask(vill);

            // Sanitize again for safety.
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, offerSize);
            if (sanitized != mask) {
                TradeLockState.setMask(vill, sanitized);
                mask = sanitized;
            }

            EZVillagerReroll.LOG().debug("[EZVR] sendCurrentTradeLocksSnapshot: containerId={} villager={} mask={}",
                    containerId, vill.getUUID(), Long.toUnsignedString(mask));

            safeReply(ctx, new PacketTradeLocks(containerId, mask));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] sendCurrentTradeLocksSnapshot failed: {}", t.toString());
        }
    }

    private static void safeReply(IPayloadContext ctx, PacketTradeLocks msg) {
        try {
            ctx.reply(msg);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] safeReply(PacketTradeLocks) failed (soft): {}", t.toString());
        }
    }
}
