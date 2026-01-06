// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/TradeLockService.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

public final class TradeLockService {

    public static PacketTradeLocks computeSnapshot(ServerPlayer player) {
        try {
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: player not in MerchantMenu (player={})",
                        player.getGameProfile().getName());
                return new PacketTradeLocks(-1, 0L);
            }

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: trader not Villager (player={}, trader={})",
                        player.getGameProfile().getName(), trader == null ? "null" : trader.getClass().getName());
                return new PacketTradeLocks(containerId, 0L);
            }

            long mask = TradeLockState.getMask(vill);

            int size = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, size);
            if (sanitized != mask) {
                TradeLockState.setMask(vill, sanitized);
                mask = sanitized;
            }

            return new PacketTradeLocks(containerId, mask);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] TradeLockService.computeSnapshot failed", t);
            return new PacketTradeLocks(-1, 0L);
        }
    }

    private TradeLockService() {}
}
