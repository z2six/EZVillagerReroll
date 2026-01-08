// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerSync.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;

public final class ServerSync {

    private ServerSync() {}

    public static void syncTo(ServerPlayer sp) {
        try {
            if (sp == null || sp.connection == null) return;

            PacketSyncConfig pkt = new PacketSyncConfig();
            pkt.version = ServerConfig.cfgVersion();
            pkt.hash = ServerConfig.cfgHash();
            pkt.costItemOrTag = (ServerConfig.costSpec == null ? "minecraft:emerald" : ServerConfig.costSpec);

            // ClientSyncedConfig expects len 6 in your current shape.
            int[] lvl5 = ServerConfig.costsByLevel5();
            int[] arr6 = new int[6];
            // indices 0..4 = levels 1..5
            for (int i = 0; i < 5 && i < lvl5.length; i++) arr6[i] = Math.max(0, lvl5[i]);
            arr6[5] = 0; // reserved slot
            pkt.costsByLevel = arr6;

            pkt.preferWallet = ServerConfig.preferWallet;
            pkt.cooldownTicks = ServerConfig.cooldownTicks;
            pkt.perVillagerDaily = ServerConfig.perVillagerDaily;

            // New fields
            pkt.freeOffers = ServerConfig.freeOffers;
            pkt.costPerOffer = ServerConfig.costPerOffer;
            pkt.maxDeductibleLockedOffers = ServerConfig.maxDeductibleLockedOffers;
            pkt.autoHourlyThreshold = ServerConfig.autoHourlyThreshold;
            pkt.autoHourlyDiscountOrIncreasePct = ServerConfig.autoHourlyDiscountOrIncreasePct;

            pkt.allowAfterTradeUsed = ServerConfig.allowAfterTradeUsed;

            sp.connection.send(new ClientboundCustomPayloadPacket(pkt));

            EZVillagerReroll.LOG().debug(
                    "[EZVR] ServerSync: sent config to {} (v={}, hash={})",
                    sp.getGameProfile().getName(), pkt.version, pkt.hash
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerSync.syncTo failed", t);
        }
    }
}
