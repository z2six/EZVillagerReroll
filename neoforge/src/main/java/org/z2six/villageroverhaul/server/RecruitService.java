// neoforge\src\main\java\org\z2six\villageroverhaul\server\RecruitService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

import java.util.UUID;

public final class RecruitService {

    public static final String TAG_RECRUITED = "villageroverhaul_recruited";
    public static final String TAG_RECRUITED_BY = "villageroverhaul_recruited_by"; // UUID
    public static final String TAG_RECRUITED_BY_NAME = "villageroverhaul_recruited_by_name"; // String (best-effort, for UI)
    public static final String TAG_RECRUITED_AT = "villageroverhaul_recruited_at"; // long gameTime

    private RecruitService() {}

    public static boolean isEligible(Villager vill) {
        try {
            if (vill == null) return false;
            if (vill.isBaby()) return false;
            // Allow recruiting merchant villagers too (profession doesn't matter).
            return true;
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

    public static UUID getRecruiterUuid(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag pd = vill.getPersistentData();
            if (pd == null) return null;
            if (!pd.hasUUID(TAG_RECRUITED_BY)) return null;
            return pd.getUUID(TAG_RECRUITED_BY);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Cost model:
     *  - Read enabled-module stats (points in [-100..100] each).
     *  - Sum in [minSum..maxSum].
     *  - Normalize alpha = (sum - minSum) / (maxSum - minSum).
     *  - Lerp from minCost (alpha=0) to maxCost (alpha=1).
     *    => very "bad" villager (negative sum) costs near min, very "good" costs near max.
     */
    public static int computeRecruitCost(Villager vill) {
        try {
            if (vill == null) return 0;

            // Ensure stats exist
            try { VillagerStatsService.ensureStats(vill); } catch (Throwable ignored) {}

            int g = 0, t = 0, i = 0, h = 0;
            int vit = 0, agi = 0, str = 0, arm = 0;
            int mot = 0, eff = 0, pw = 0, ran = 0;

            CompoundTag pd = vill.getPersistentData();
            if (pd != null && pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);
                g = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
                t = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
                i = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
                h = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));

                vit = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_VITALITY));
                agi = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_AGILITY));
                str = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_STRENGTH));
                arm = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_ARMOR));

                mot = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_MOTIVATION));
                eff = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_EFFICIENCY));
                pw  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_PLANT_WHISPERER));
                ran = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_RANGER));
            }

            int sum = 0;
            int statCount = 0;
            if (ServerConfig.enableMerchantModule) {
                sum += g + t + i + h;
                statCount += 4;
            }
            if (ServerConfig.enableCombatModule) {
                sum += vit + agi + str + arm;
                statCount += 4;
            }
            if (ServerConfig.enableFarmingModule) {
                sum += mot + eff + pw + ran;
                statCount += 4;
            }
            if (statCount <= 0) statCount = 1;
            int minSum = -100 * statCount;
            int maxSum = 100 * statCount;
            if (sum < minSum) sum = minSum;
            if (sum > maxSum) sum = maxSum;

            int minCost = Math.max(0, ServerConfig.recruitCostMin);
            int maxCost = Math.max(0, ServerConfig.recruitCostMax);
            if (minCost > maxCost) { int tmp = minCost; minCost = maxCost; maxCost = tmp; }

            if (minCost == maxCost) return minCost;

            double alpha = (sum - (double) minSum) / ((double) maxSum - (double) minSum);
            if (alpha < 0.0) alpha = 0.0;
            if (alpha > 1.0) alpha = 1.0;

            // alpha=0 => minCost, alpha=1 => maxCost
            double costD = minCost + (maxCost - minCost) * alpha;
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
                if (sp != null && sp.getGameProfile() != null) {
                    String n = sp.getGameProfile().getName();
                    if (n != null) pd.putString(TAG_RECRUITED_BY_NAME, n);
                }
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
