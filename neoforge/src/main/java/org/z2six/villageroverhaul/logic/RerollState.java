// neoforge\src\main\java\org\z2six\villageroverhaul\logic\RerollState.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.server.VillagerStatsService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RerollState {

    private static final Map<UUID, Long> lastTick = new HashMap<>();

    // ---  persistent daily cap keys ---
    private static final String TAG_ROOT = "ezvr";
    private static final String TAG_DAILY = "dailyReroll";
    private static final String TAG_DAY_IDX = "midnightDayIdx";
    private static final String TAG_REMAINING = "remaining";

    public static boolean canReroll(ServerPlayer sp, Villager vill) {
        if (sp == null || vill == null) return false;
        return canReroll(sp.serverLevel(), vill);
    }

    public static boolean canReroll(ServerLevel lvl, Villager vill) {
        if (lvl == null || vill == null) return false;

        // cooldown (unchanged)
        long now = lvl.getGameTime();
        int cooldown = effectiveCooldownTicks(vill);
        if (cooldown > 0) {
            long last = lastTick.getOrDefault(vill.getUUID(), Long.MIN_VALUE);
            if (last != Long.MIN_VALUE && now - last < cooldown) return false;
        }

        // daily cap (, persistent, midnight reset)
        int cap = Math.max(0, ServerConfig.perVillagerDaily);
        if (cap > 0) {
            int remaining = getDailyRemaining(lvl, vill);
            if (remaining <= 0) return false;
        }

        return true;
    }

    public static void markRerolled(ServerPlayer sp, Villager vill) {
        if (sp == null || vill == null) return;
        markRerolled(sp.serverLevel(), vill, true);
    }

    /**
     * @param countsTowardDailyCap true for MANUAL reroll, false for SearchService auto rerolls
     */
    public static void markRerolled(ServerLevel lvl, Villager vill, boolean countsTowardDailyCap) {
        if (lvl == null || vill == null) return;

        long now = lvl.getGameTime();
        UUID id = vill.getUUID();
        lastTick.put(id, now);

        if (countsTowardDailyCap) {
            int cap = Math.max(0, ServerConfig.perVillagerDaily);
            if (cap > 0) {
                consumeDailyReroll(lvl, vill, cap);
            }
        }
    }

    // -----------------------------
    // Daily cap getters (server-authoritative)
    // -----------------------------

    public static int getDailyCap(ServerLevel lvl, Villager vill) {
        return Math.max(0, ServerConfig.perVillagerDaily);
    }

    public static int getDailyRemaining(ServerLevel lvl, Villager vill) {
        int cap = Math.max(0, ServerConfig.perVillagerDaily);
        if (cap <= 0) return 0;

        ensureDailyDataUpToDate(lvl, vill, cap);
        CompoundTag daily = getDailyTag(vill);
        int rem = daily.getInt(TAG_REMAINING);
        if (rem < 0) rem = 0;
        if (rem > cap) rem = cap; // config may change
        return rem;
    }

    private static void consumeDailyReroll(ServerLevel lvl, Villager vill, int cap) {
        ensureDailyDataUpToDate(lvl, vill, cap);
        CompoundTag daily = getDailyTag(vill);

        int rem = daily.getInt(TAG_REMAINING);
        if (rem < 0) rem = 0;
        if (rem > cap) rem = cap;

        if (rem > 0) rem--;

        daily.putInt(TAG_REMAINING, rem);
    }

    /**
     * Reset at Minecraft 00:00 (midnight). In vanilla time:
     * - 0    = 06:00
     * - 6000 = 12:00
     * - 12000= 18:00
     * - 18000= 00:00 (midnight)
     *
     * We want the "day index" to advance exactly at 18000, so:
     *   idx = (dayTime + 6000) / 24000
     */
    private static long midnightDayIndex(ServerLevel lvl) {
        long dayTime = lvl.getDayTime(); // includes day progression, not just modulo
        return (dayTime + 6000L) / 24000L;
    }

    private static void ensureDailyDataUpToDate(ServerLevel lvl, Villager vill, int cap) {
        if (lvl == null || vill == null) return;

        CompoundTag daily = getDailyTag(vill);

        long idxNow = midnightDayIndex(lvl);
        long idxStored = daily.contains(TAG_DAY_IDX, Tag.TAG_LONG) ? daily.getLong(TAG_DAY_IDX) : Long.MIN_VALUE;

        if (idxStored != idxNow) {
            // new “midnight day” → reset remaining to cap
            daily.putLong(TAG_DAY_IDX, idxNow);
            daily.putInt(TAG_REMAINING, cap);
            return;
        }

        // Ensure remaining exists + clamp
        if (!daily.contains(TAG_REMAINING, Tag.TAG_INT)) {
            daily.putInt(TAG_REMAINING, cap);
        } else {
            int rem = daily.getInt(TAG_REMAINING);
            if (rem < 0) rem = 0;
            if (rem > cap) rem = cap;
            daily.putInt(TAG_REMAINING, rem);
        }
    }

    private static CompoundTag getDailyTag(Villager vill) {
        // vill.getPersistentData() exists on NeoForge entities
        CompoundTag root = vill.getPersistentData();

        CompoundTag ezvr;
        if (root.contains(TAG_ROOT, Tag.TAG_COMPOUND)) {
            ezvr = root.getCompound(TAG_ROOT);
        } else {
            ezvr = new CompoundTag();
            root.put(TAG_ROOT, ezvr);
        }

        CompoundTag daily;
        if (ezvr.contains(TAG_DAILY, Tag.TAG_COMPOUND)) {
            daily = ezvr.getCompound(TAG_DAILY);
        } else {
            daily = new CompoundTag();
            ezvr.put(TAG_DAILY, daily);
        }

        return daily;
    }

    // cooldown helpers (unchanged)
    private static int effectiveCooldownTicks(Villager vill) {
        try {
            int base = ServerConfig.cooldownTicks;
            if (base <= 0) return 0;

            if (vill != null) {
                try { VillagerStatsService.ensureStats(vill); } catch (Throwable ignored) {}
                double pct = 0.0;
                try { pct = VillagerTraitEffects.timelinessPct(vill); } catch (Throwable ignored) { pct = 0.0; }

                int eff = VillagerTraitEffects.applyCooldownPercent(base, pct);
                if (eff <= 0) eff = 1;
                return eff;
            }

            return base;
        } catch (Throwable t) {
            return Math.max(0, ServerConfig.cooldownTicks);
        }
    }

    public static int cooldownRemainingTicks(ServerLevel lvl, Villager vill) {
        try {
            if (lvl == null || vill == null) return 0;

            int cooldown = effectiveCooldownTicks(vill);
            if (cooldown <= 0) return 0;

            long now = lvl.getGameTime();
            long last = lastTick.getOrDefault(vill.getUUID(), Long.MIN_VALUE);
            if (last == Long.MIN_VALUE) return 0;

            long elapsed = now - last;
            if (elapsed >= cooldown) return 0;

            long rem = cooldown - elapsed;
            if (rem < 0) rem = 0;
            if (rem > Integer.MAX_VALUE) rem = Integer.MAX_VALUE;
            return (int) rem;
        } catch (Throwable t) {
            return 0;
        }
    }

    private RerollState() {}
}
