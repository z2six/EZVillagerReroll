package org.z2six.ezvillagerreroll.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.server.VillagerStatsService;

/**
 * Server-side trait effect helpers.
 *
 * Converts villager "points" [-100..100] into a percent value using ServerConfig bounds:
 *   percent = lerp(minPct, maxPct, (points + 100) / 200.0)
 *
 * Then applies these percents to:
 * - Cost (Generosity): multiplier = 1 - pct/100
 * - Cooldown (Timeliness): multiplier = 1 - pct/100
 * - XP (Intellect): multiplier = 1 + pct/100
 */
public final class VillagerTraitEffects {

    private VillagerTraitEffects() {}

    // -----------------------------------------------------------------------------------------
    // Public API: percents
    // -----------------------------------------------------------------------------------------

    public static double generosityPct(Villager vill) {
        int pts = getPointsSafe(vill, VillagerStatsService.K_GENEROSITY);
        return pointsToPct(pts, ServerConfig.generosityMinPct, ServerConfig.generosityMaxPct);
    }

    public static double timelinessPct(Villager vill) {
        int pts = getPointsSafe(vill, VillagerStatsService.K_TIMELINESS);
        return pointsToPct(pts, ServerConfig.timelinessMinPct, ServerConfig.timelinessMaxPct);
    }

    public static double intellectPct(Villager vill) {
        int pts = getPointsSafe(vill, VillagerStatsService.K_INTELLECT);
        return pointsToPct(pts, ServerConfig.intellectMinPct, ServerConfig.intellectMaxPct);
    }

    // -----------------------------------------------------------------------------------------
    // Public API: apply effects
    // -----------------------------------------------------------------------------------------

    /** Applies "discount" style percent where positive reduces cost. */
    public static int applyCostPercent(int baseCost, double pct) {
        if (baseCost <= 0) return Math.max(0, baseCost);
        double mult = 1.0 - (safePct(pct) / 100.0);
        if (mult < 0.0) mult = 0.0;

        double raw = (double) baseCost * mult;
        long out = Math.round(raw);

        if (out < 0L) out = 0L;
        if (out > Integer.MAX_VALUE) out = Integer.MAX_VALUE;
        return (int) out;
    }

    /** Applies "faster" style percent where positive reduces ticks. Always returns >= 1 when baseTicks >= 1. */
    public static int applyCooldownPercent(int baseTicks, double pct) {
        int base = Math.max(1, baseTicks);
        double mult = 1.0 - (safePct(pct) / 100.0);
        if (mult < 0.0) mult = 0.0;

        double raw = (double) base * mult;
        long out = Math.round(raw);

        if (out < 1L) out = 1L;
        if (out > Integer.MAX_VALUE) out = Integer.MAX_VALUE;
        return (int) out;
    }

    /**
     * Applies XP multiplier:
     *  xpFinal = baseXp * (1 + intellectPct/100)
     */
    public static int applyXpPercentsRounded(double baseXp, double intellectPct) {
        if (baseXp <= 0.0) return 0;

        double i = 1.0 + (safePct(intellectPct) / 100.0);

        // Do not allow negative multipliers to invert XP.
        if (i < 0.0) i = 0.0;

        double raw = baseXp * i;
        long out = Math.round(raw);

        if (out < 0L) out = 0L;
        if (out > Integer.MAX_VALUE) out = Integer.MAX_VALUE;
        return (int) out;
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private static double pointsToPct(int points, double minPct, double maxPct) {
        int p = VillagerStatsService.clampPoints(points);
        double t = (p + 100) / 200.0; // -100..100 => 0..1
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        double min = safePct(minPct);
        double max = safePct(maxPct);
        return min + (max - min) * t;
    }

    private static int getPointsSafe(Villager vill, String key) {
        try {
            if (vill == null || key == null) return 0;

            // ensure stats exist (no-op if already there)
            VillagerStatsService.ensureStats(vill);

            CompoundTag pd = vill.getPersistentData();
            if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) return 0;

            CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);
            if (root == null) return 0;

            return VillagerStatsService.clampPoints(root.getInt(key));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static double safePct(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        // bounds are already normalized in ServerConfig.reload(), but clamp just in case
        if (v < -10000.0) return -10000.0;
        if (v > 10000.0) return 10000.0;
        return v;
    }
}
