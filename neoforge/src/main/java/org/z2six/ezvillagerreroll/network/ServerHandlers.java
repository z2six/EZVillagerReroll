// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.server.TradeLockSyncService;

public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) {
                EZVillagerReroll.LOG().warn("[EZVR] Reroll request without ServerPlayer context");
                return;
            }
            RerollExecutor.tryReroll(sp);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll exception", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock without ServerPlayer context");
                return;
            }

            int traderId = msg.traderEntityId();
            int idx = msg.tradeIndex();

            if (idx < 0 || idx >= 63) { // we store in a long bitmask; keep safe
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock rejected: invalid index={} (player={})", idx, sp.getGameProfile().getName());
                return;
            }

            Villager vill = null;

            // Prefer resolving through current MerchantMenu (prevents spoofing other villager IDs)
            if (sp.containerMenu instanceof MerchantMenu menu) {
                try {
                    var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                    if (trader instanceof Villager v) vill = v;
                    else if (trader instanceof Entity ent && ent instanceof Villager v2) vill = v2;
                } catch (Throwable ignored) {}
            }

            // Fallback: resolve from world entity id, but still validate it's a villager
            if (vill == null && traderId >= 0) {
                try {
                    Entity e = sp.level().getEntity(traderId);
                    if (e instanceof Villager v) vill = v;
                } catch (Throwable ignored) {}
            }

            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock failed: could not resolve villager (player={}, traderId={})",
                        sp.getGameProfile().getName(), traderId);
                return;
            }

            // Ensure index is within current offer size (or allow locking empty spots? better: reject)
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            if (idx >= offerSize) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock rejected: idx={} >= offers={} (villager={}, player={})",
                        idx, offerSize, vill.getUUID(), sp.getGameProfile().getName());
                return;
            }

            long before = TradeLockState.getMask(vill);
            long after = TradeLockState.toggle(vill, idx);

            EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: villager={} idx={} {} -> {} (player={})",
                    vill.getUUID(), idx,
                    Long.toUnsignedString(before),
                    Long.toUnsignedString(after),
                    sp.getGameProfile().getName()
            );

            // Sync to all players currently trading with that villager (including this player)
            TradeLockSyncService.syncToActiveTraders(vill, after);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock exception", t);
        }
    }
}
