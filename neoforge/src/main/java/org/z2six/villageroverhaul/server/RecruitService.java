package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

public final class RecruitService {

    public static final String TAG_RECRUITED = "villageroverhaul_recruited";
    public static final String TAG_RECRUITED_BY = "villageroverhaul_recruited_by"; // UUID
    public static final String TAG_RECRUITED_AT = "villageroverhaul_recruited_at"; // long gameTime

    private RecruitService() {}

    public static boolean isEligible(Villager vill) {
        try {
            if (vill == null) return false;
            if (vill.isBaby()) return false;

            // Must be unemployed (not a merchant yet)
            return vill.getVillagerData().getProfession() == VillagerProfession.NONE;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRecruited(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag pd = vill.getPersistentData();
            return pd != null && pd.getBoolean(TAG_RECRUITED);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Cost model:
     *  - Read 4 stats (points in [-100..100] each).
     *  - Sum in [-400..400].
     *  - Normalize alpha = (sum + 400) / 800.
     *  - Lerp from maxCost (alpha=0) to minCost (alpha=1).
     *    => very "bad" villager (negative sum) costs near max, very "good" costs near min.
     */
    public static int computeRecruitCost(Villager vill) {
        try {
            if (vill == null) return 0;

            // Ensure stats exist
            try { VillagerStatsService.ensureStats(vill); } catch (Throwable ignored) {}

            int g = 0, t = 0, i = 0, h = 0;

            CompoundTag pd = vill.getPersistentData();
            if (pd != null && pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);
                g = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
                t = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
                i = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
                h = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));
            }

            int sum = g + t + i + h; // [-400..400]
            if (sum < -400) sum = -400;
            if (sum > 400) sum = 400;

            int minCost = Math.max(0, ServerConfig.recruitCostMin);
            int maxCost = Math.max(0, ServerConfig.recruitCostMax);
            if (minCost > maxCost) { int tmp = minCost; minCost = maxCost; maxCost = tmp; }

            if (minCost == maxCost) return minCost;

            double alpha = (sum + 400.0) / 800.0;
            if (alpha < 0.0) alpha = 0.0;
            if (alpha > 1.0) alpha = 1.0;

            // alpha=0 => maxCost, alpha=1 => minCost
            double costD = maxCost + (minCost - maxCost) * alpha;
            long costL = Math.round(costD);

            if (costL < minCost) costL = minCost;
            if (costL > maxCost) costL = maxCost;
            if (costL > Integer.MAX_VALUE) costL = Integer.MAX_VALUE;

            return (int) costL;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitService.computeRecruitCost failed (soft): {}", t.toString());
            return Math.max(0, ServerConfig.recruitCostMin);
        }
    }

    public static boolean markRecruited(ServerPlayer sp, Villager vill) {
        try {
            if (vill == null) return false;

            CompoundTag pd = vill.getPersistentData();
            if (pd == null) return false;

            pd.putBoolean(TAG_RECRUITED, true);

            try {
                if (sp != null) pd.putUUID(TAG_RECRUITED_BY, sp.getUUID());
            } catch (Throwable ignored) {}

            try {
                long gt = vill.level() != null ? vill.level().getGameTime() : 0L;
                pd.putLong(TAG_RECRUITED_AT, gt);
            } catch (Throwable ignored) {}

            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RecruitService.markRecruited failed", t);
            return false;
        }
    }
}
