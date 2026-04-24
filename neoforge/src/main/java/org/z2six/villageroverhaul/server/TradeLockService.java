// neoforge\src\main\java\org\z2six\villageroverhaul\server\TradeLockService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.logic.MerchantCompatibility;
import org.z2six.villageroverhaul.logic.TradeLockState;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;

public final class TradeLockService {

    public static PacketTradeLocks computeSnapshot(ServerPlayer player) {
        try {
            if (!(player.containerMenu instanceof MerchantMenu menu)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] TradeLockService snapshot: player not in MerchantMenu (player={})",
                        player.getGameProfile().getName());
                return new PacketTradeLocks(-1, 0L);
            }

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Entity entity) || !MerchantCompatibility.supportsTradeLocks(entity)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] TradeLockService snapshot: trader not Villager (player={}, trader={})",
                        player.getGameProfile().getName(), trader == null ? "null" : trader.getClass().getName());
                return new PacketTradeLocks(containerId, 0L);
            }

            long mask = TradeLockState.getMask(entity);

            int size = MerchantCompatibility.offerCount(entity);
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, size);
            if (sanitized != mask) {
                TradeLockState.setMask(entity, sanitized);
                mask = sanitized;
            }

            return new PacketTradeLocks(containerId, mask);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] TradeLockService.computeSnapshot failed", t);
            return new PacketTradeLocks(-1, 0L);
        }
    }

    private TradeLockService() {}
}
