// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ClientSyncedConfig.java
package org.z2six.ezvillagerreroll.network;

import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.Arrays;

public final class ClientSyncedConfig {

    public static final class Snapshot {
        public int version;
        public int hash;
        public String costItemOrTag;
        public int[] costsByLevel; // len 6
        public boolean preferWallet;
        public int cooldownTicks;
        public int perVillagerDaily;

        // New (required for client-side displays; server remains authoritative)
        public int freeOffers;
        public int costPerOffer;
        public int maxDeductibleLockedOffers;
        public int autoHourlyThreshold;
        public double autoHourlyDiscountOrIncreasePct;

        public boolean allowAfterTradeUsed;

        @Override
        public String toString() {
            return "Snapshot{" +
                    "version=" + version +
                    ", hash=" + hash +
                    ", costItemOrTag='" + costItemOrTag + '\'' +
                    ", costsByLevel=" + Arrays.toString(costsByLevel) +
                    ", preferWallet=" + preferWallet +
                    ", cooldownTicks=" + cooldownTicks +
                    ", perVillagerDaily=" + perVillagerDaily +
                    ", freeOffers=" + freeOffers +
                    ", costPerOffer=" + costPerOffer +
                    ", maxDeductibleLockedOffers=" + maxDeductibleLockedOffers +
                    ", autoHourlyThreshold=" + autoHourlyThreshold +
                    ", autoHourlyDiscountOrIncreasePct=" + autoHourlyDiscountOrIncreasePct +
                    ", allowAfterTradeUsed=" + allowAfterTradeUsed +
                    '}';
        }
    }

    private static volatile Snapshot last;

    // Your existing method name:
    public static void setFrom(PacketSyncConfig msg) {
        try {
            Snapshot s = new Snapshot();
            s.version = msg.version;
            s.hash = msg.hash;
            s.costItemOrTag = msg.costItemOrTag;
            s.costsByLevel = msg.costsByLevel != null ? msg.costsByLevel.clone() : new int[]{0,0,0,0,0,0};
            if (s.costsByLevel.length != 6) {
                int[] fixed = new int[6];
                for (int i = 0; i < 6 && i < s.costsByLevel.length; i++) fixed[i] = Math.max(0, s.costsByLevel[i]);
                s.costsByLevel = fixed;
            }
            s.preferWallet = msg.preferWallet;
            s.cooldownTicks = msg.cooldownTicks;
            s.perVillagerDaily = msg.perVillagerDaily;

            s.freeOffers = Math.max(0, msg.freeOffers);
            s.costPerOffer = Math.max(0, msg.costPerOffer);
            s.maxDeductibleLockedOffers = Math.max(0, msg.maxDeductibleLockedOffers);
            s.autoHourlyThreshold = Math.max(0, msg.autoHourlyThreshold);
            s.autoHourlyDiscountOrIncreasePct = Math.max(0.0, msg.autoHourlyDiscountOrIncreasePct);

            s.allowAfterTradeUsed = msg.allowAfterTradeUsed;

            last = s;

            EZVillagerReroll.LOG().info("[EZVR] Client received synced SERVER config: {}", s);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Failed to apply synced config on client", t);
        }
    }

    // Network.java calls applyFromServer(...) in your pasted code -> provide it:
    public static void applyFromServer(PacketSyncConfig msg) {
        setFrom(msg);
    }

    public static Snapshot get() {
        return last;
    }

    private ClientSyncedConfig() {}
}
