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

        public int recruitCostMin, recruitCostMax;

        // NEW: combat bounds
        public double vitalityMinHealth, vitalityMaxHealth;
        public double agilityMinSpeed, agilityMaxSpeed;
        public double strengthMinDamage, strengthMaxDamage;
        public double armorMin, armorMax;

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
                    ", vitalityHealth=[" + vitalityMinHealth + "," + vitalityMaxHealth + "]" +
                    ", agilitySpeed=[" + agilityMinSpeed + "," + agilityMaxSpeed + "]" +
                    ", strengthDamage=[" + strengthMinDamage + "," + strengthMaxDamage + "]" +
                    ", armor=[" + armorMin + "," + armorMax + "]" +
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

            int rMin = Math.max(0, msg.recruitCostMin);
            int rMax = Math.max(0, msg.recruitCostMax);
            if (rMin > rMax) { int tmp = rMin; rMin = rMax; rMax = tmp; }
            s.recruitCostMin = rMin;
            s.recruitCostMax = rMax;

            // NEW: combat bounds (normalize each pair)
            double vMin = msg.vitalityMinHealth, vMax = msg.vitalityMaxHealth;
            if (Double.isNaN(vMin)) vMin = 0.0;
            if (Double.isNaN(vMax)) vMax = 0.0;
            if (vMin > vMax) { double tmp = vMin; vMin = vMax; vMax = tmp; }
            s.vitalityMinHealth = vMin;
            s.vitalityMaxHealth = vMax;

            double aMin = msg.agilityMinSpeed, aMax = msg.agilityMaxSpeed;
            if (Double.isNaN(aMin)) aMin = 0.0;
            if (Double.isNaN(aMax)) aMax = 0.0;
            if (aMin > aMax) { double tmp = aMin; aMin = aMax; aMax = tmp; }
            s.agilityMinSpeed = aMin;
            s.agilityMaxSpeed = aMax;

            double stMin = msg.strengthMinDamage, stMax = msg.strengthMaxDamage;
            if (Double.isNaN(stMin)) stMin = 0.0;
            if (Double.isNaN(stMax)) stMax = 0.0;
            if (stMin > stMax) { double tmp = stMin; stMin = stMax; stMax = tmp; }
            s.strengthMinDamage = stMin;
            s.strengthMaxDamage = stMax;

            double arMin = msg.armorMin, arMax = msg.armorMax;
            if (Double.isNaN(arMin)) arMin = 0.0;
            if (Double.isNaN(arMax)) arMax = 0.0;
            if (arMin > arMax) { double tmp = arMin; arMin = arMax; arMax = tmp; }
            s.armorMin = arMin;
            s.armorMax = arMax;

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
