// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/ServerSync.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

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

            // NEW (double)
            pkt.manualRerollXpPerOffer = ServerConfig.manualRerollXpPerOffer;

            // NEW: trait bounds
            pkt.generosityMinPct = ServerConfig.generosityMinPct;
            pkt.generosityMaxPct = ServerConfig.generosityMaxPct;

            pkt.timelinessMinPct = ServerConfig.timelinessMinPct;
            pkt.timelinessMaxPct = ServerConfig.timelinessMaxPct;

            pkt.intellectMinPct = ServerConfig.intellectMinPct;
            pkt.intellectMaxPct = ServerConfig.intellectMaxPct;

            pkt.hoarderMinPct = ServerConfig.hoarderMinPct;
            pkt.hoarderMaxPct = ServerConfig.hoarderMaxPct;

            sp.connection.send(new ClientboundCustomPayloadPacket(pkt));

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] ServerSync: sent config to {} (v={}, hash={})",
                    sp.getGameProfile().getName(), pkt.version, pkt.hash
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerSync.syncTo failed", t);
        }
    }
}
