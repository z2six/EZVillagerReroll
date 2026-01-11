package org.z2six.villageroverhaul.network;

import org.z2six.villageroverhaul.VillagerOverhaul;

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

        public int freeOffers;
        public int costPerOffer;
        public int maxDeductibleLockedOffers;
        public int autoHourlyThreshold;
        public double autoHourlyDiscountOrIncreasePct;

        public boolean allowAfterTradeUsed;

        public double manualRerollXpPerOffer;

        public double generosityMinPct, generosityMaxPct;
        public double timelinessMinPct, timelinessMaxPct;
        public double intellectMinPct, intellectMaxPct;

        public int hoarderExtraOffersMin, hoarderExtraOffersMax;

        // NEW: recruit bounds
        public int recruitCostMin, recruitCostMax;

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
                    ", manualRerollXpPerOffer=" + manualRerollXpPerOffer +
                    ", generosity=[" + generosityMinPct + "," + generosityMaxPct + "]" +
                    ", timeliness=[" + timelinessMinPct + "," + timelinessMaxPct + "]" +
                    ", intellect=[" + intellectMinPct + "," + intellectMaxPct + "]" +
                    ", hoarderClamp=[" + hoarderExtraOffersMin + "," + hoarderExtraOffersMax + "]" +
                    ", recruitCost=[" + recruitCostMin + "," + recruitCostMax + "]" +
                    '}';
        }
    }

    private static volatile Snapshot last;

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

            s.manualRerollXpPerOffer = Math.max(0.0, msg.manualRerollXpPerOffer);

            s.generosityMinPct = msg.generosityMinPct;
            s.generosityMaxPct = msg.generosityMaxPct;

            s.timelinessMinPct = msg.timelinessMinPct;
            s.timelinessMaxPct = msg.timelinessMaxPct;

            s.intellectMinPct = msg.intellectMinPct;
            s.intellectMaxPct = msg.intellectMaxPct;

            int hMin = msg.hoarderExtraOffersMin;
            int hMax = msg.hoarderExtraOffersMax;
            if (hMin > hMax) { int tmp = hMin; hMin = hMax; hMax = tmp; }
            s.hoarderExtraOffersMin = hMin;
            s.hoarderExtraOffersMax = hMax;

            // NEW: recruit bounds
            int rMin = Math.max(0, msg.recruitCostMin);
            int rMax = Math.max(0, msg.recruitCostMax);
            if (rMin > rMax) { int tmp = rMin; rMin = rMax; rMax = tmp; }
            s.recruitCostMin = rMin;
            s.recruitCostMax = rMax;

            last = s;

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Client received synced SERVER config: {}", s);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Failed to apply synced config on client", t);
        }
    }

    public static void applyFromServer(PacketSyncConfig msg) {
        setFrom(msg);
    }

    public static Snapshot get() {
        return last;
    }

    private ClientSyncedConfig() {}
}
