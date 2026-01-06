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

public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            RerollExecutor.tryReroll(sp);

            // Optional: after reroll, send current lock mask snapshot so client stays correct
            // (harmless even if unchanged)
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

            int idx = msg.tradeIndex();
            if (idx < 0 || idx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock: invalid idx={} (player={})", idx, sp.getGameProfile().getName());
                return;
            }

            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().debug("[EZVR] ToggleTradeLock ignored: player not in MerchantMenu (player={})", sp.getGameProfile().getName());
                return;
            }

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                EZVillagerReroll.LOG().debug("[EZVR] ToggleTradeLock ignored: trader is not Villager (player={}, trader={})",
                        sp.getGameProfile().getName(), trader == null ? "null" : trader.getClass().getName());
                return;
            }

            long next = TradeLockState.toggle(vill, idx);

            // sanitize just in case offers shrunk / changed
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(next, offerSize);
            if (sanitized != next) {
                TradeLockState.setMask(vill, sanitized);
                next = sanitized;
            }

            EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: OK (player={}, villager={}, idx={}, mask={})",
                    sp.getGameProfile().getName(), vill.getUUID(), idx, Long.toUnsignedString(next));

            // Reply to the toggling player so the client UI updates immediately
            try {
                ctx.reply(new PacketTradeLocks(vill.getId(), next));
                EZVillagerReroll.LOG().debug("[EZVR] ToggleTradeLock: replied PacketTradeLocks(traderId={}, mask={})",
                        vill.getId(), Long.toUnsignedString(next));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock: reply PacketTradeLocks failed (soft): {}", t.toString());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock failed", t);
        }
    }

    private static void sendCurrentTradeLocksSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;
            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            long mask = TradeLockState.getMask(vill);
            ctx.reply(new PacketTradeLocks(vill.getId(), mask));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] sendCurrentTradeLocksSnapshot failed: {}", t.toString());
        }
    }
}
