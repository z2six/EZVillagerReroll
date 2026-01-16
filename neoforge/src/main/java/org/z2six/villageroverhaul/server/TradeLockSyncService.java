// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/TradeLockSyncService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;

public final class TradeLockSyncService {

    private TradeLockSyncService() {}

    /**
     * Sends the current lock mask to every player who is actively trading with this villager.
     * This is what makes it "other players can see it as well" (server authoritative).
     */
    public static void syncToActiveTraders(Villager vill, long mask) {
        try {
            if (vill == null) return;
            MinecraftServer server = vill.getServer();
            if (server == null) return;

            int villEntityId = vill.getId();
            PacketTradeLocks pkt = new PacketTradeLocks(villEntityId, mask);

            int sent = 0;

            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                try {
                    if (!(sp.containerMenu instanceof MerchantMenu mm)) continue;
                    var trader = ((MerchantMenuAccessor) mm).ezvr$getTrader();
                    if (trader != vill) continue;

                    if (sp.connection != null) {
                        sp.connection.send(new ClientboundCustomPayloadPacket(pkt));
                        sent++;
                    }
                } catch (Throwable ignored) {}
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] TradeLockSyncService: synced mask={} villager={} to {} active trader(s)",
                    Long.toUnsignedString(mask), vill.getUUID(), sent);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] TradeLockSyncService.syncToActiveTraders failed", t);
        }
    }
}
