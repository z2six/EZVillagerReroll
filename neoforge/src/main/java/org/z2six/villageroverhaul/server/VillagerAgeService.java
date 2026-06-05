package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class VillagerAgeService {

    public static final String TAG_ROOT = "ezvr_life";
    public static final String K_BIRTH_GAME_TIME = "birth_game_time";
    public static final String K_BIRTH_DAY = "birth_day";

    private static final long VANILLA_TICKS_PER_DAY = 24000L;
    private static final String TIMELINE_MOD_ID = "rpgtimeline";
    private static final String TIMELINE_API_CLASS = "org.z2six.rpgtimeline.api.RPGTimelineApi";

    private VillagerAgeService() {}

    public record AgeDisplay(String birthDateText, String ageText) {
        public static AgeDisplay unavailable() {
            return new AgeDisplay("", "");
        }
    }

    public static void ensureBirthData(Entity entity) {
        try {
            if (entity == null) return;
            if (!VillagerStatsService.isSupportedMerchantEntity(entity)) return;
            CompoundTag pd = entity.getPersistentData();
            if (pd == null) return;

            CompoundTag root = getOrCreateRoot(pd);
            if (root.contains(K_BIRTH_GAME_TIME, Tag.TAG_LONG) && root.contains(K_BIRTH_DAY, Tag.TAG_LONG)) {
                return;
            }

            long gameTime = currentGameTime(entity);
            long day = vanillaDayIndex(gameTime);

            if (!root.contains(K_BIRTH_GAME_TIME, Tag.TAG_LONG)) root.putLong(K_BIRTH_GAME_TIME, gameTime);
            if (!root.contains(K_BIRTH_DAY, Tag.TAG_LONG)) root.putLong(K_BIRTH_DAY, day);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerAgeService.ensureBirthData failed (soft): {}", t.toString());
        }
    }

    public static AgeDisplay getDisplay(Entity entity) {
        try {
            if (entity == null) return AgeDisplay.unavailable();
            if (!VillagerStatsService.isSupportedMerchantEntity(entity)) return AgeDisplay.unavailable();
            ensureBirthData(entity);
            CompoundTag pd = entity.getPersistentData();
            return describeFromPersistentData(pd, currentGameTime(entity));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerAgeService.getDisplay failed (soft): {}", t.toString());
            return AgeDisplay.unavailable();
        }
    }

    public static AgeDisplay describeFromPersistentData(CompoundTag persistentData, long currentGameTime) {
        try {
            if (persistentData == null || !persistentData.contains(TAG_ROOT, Tag.TAG_COMPOUND)) {
                return AgeDisplay.unavailable();
            }

            CompoundTag root = persistentData.getCompound(TAG_ROOT);
            if (!root.contains(K_BIRTH_GAME_TIME, Tag.TAG_LONG) && !root.contains(K_BIRTH_DAY, Tag.TAG_LONG)) {
                return AgeDisplay.unavailable();
            }

            long birthGameTime = root.contains(K_BIRTH_GAME_TIME, Tag.TAG_LONG)
                    ? root.getLong(K_BIRTH_GAME_TIME)
                    : root.getLong(K_BIRTH_DAY) * VANILLA_TICKS_PER_DAY;
            long birthDay = root.contains(K_BIRTH_DAY, Tag.TAG_LONG)
                    ? root.getLong(K_BIRTH_DAY)
                    : vanillaDayIndex(birthGameTime);
            long safeCurrentGameTime = currentGameTime >= 0L ? currentGameTime : birthGameTime;

            AgeDisplay timelineDisplay = tryTimelineDisplay(birthGameTime, safeCurrentGameTime);
            if (timelineDisplay != null) return timelineDisplay;

            long currentDay = vanillaDayIndex(safeCurrentGameTime);
            long ageDays = Math.max(0L, currentDay - birthDay);
            return new AgeDisplay("Day " + Math.max(0L, birthDay), formatDayAge(ageDays));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerAgeService.describeFromPersistentData failed (soft): {}", t.toString());
            return AgeDisplay.unavailable();
        }
    }

    private static CompoundTag getOrCreateRoot(CompoundTag persistentData) {
        if (!persistentData.contains(TAG_ROOT, Tag.TAG_COMPOUND)) {
            persistentData.put(TAG_ROOT, new CompoundTag());
        }
        return persistentData.getCompound(TAG_ROOT);
    }

    private static long currentGameTime(Entity entity) {
        try {
            return entity != null && entity.level() != null ? Math.max(0L, entity.level().getDayTime()) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long vanillaDayIndex(long gameTime) {
        return Math.max(0L, Math.floorDiv(Math.max(0L, gameTime), VANILLA_TICKS_PER_DAY));
    }

    private static AgeDisplay tryTimelineDisplay(long birthGameTime, long currentGameTime) {
        try {
            if (!isTimelineLoaded()) return null;

            Class<?> api = Class.forName(TIMELINE_API_CLASS);
            Method buildDate = api.getMethod("buildDateStringFromGameTime", long.class);
            Method dayIndexForGameTime = api.getMethod("getDayIndexForGameTime", long.class);

            Object birthDateObj = buildDate.invoke(null, Math.max(0L, birthGameTime));
            String birthDate = birthDateObj == null ? "" : birthDateObj.toString();
            if (birthDate.isBlank()) return null;

            long birthDay = toLong(dayIndexForGameTime.invoke(null, Math.max(0L, birthGameTime)));
            long currentDay = toLong(dayIndexForGameTime.invoke(null, Math.max(0L, currentGameTime)));
            long ageDays = Math.max(0L, currentDay - birthDay);

            return new AgeDisplay(birthDate, formatTimelineAge(api, ageDays));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Timeline age compatibility unavailable (soft): {}", t.toString());
            return null;
        }
    }

    private static boolean isTimelineLoaded() {
        try {
            ModList mods = ModList.get();
            return mods != null && mods.isLoaded(TIMELINE_MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String formatTimelineAge(Class<?> api, long ageDays) {
        try {
            Object def = api.getMethod("getCalendarDefinition").invoke(null);
            if (def == null) return formatDayAge(ageDays);

            int daysPerMonth = Math.max(1, toInt(def.getClass().getMethod("getDaysPerMonth").invoke(def)));
            int monthsPerYear = Math.max(1, toInt(def.getClass().getMethod("getMonthCount").invoke(def)));
            long daysPerYear = Math.max(1L, (long) daysPerMonth * (long) monthsPerYear);

            long years = ageDays / daysPerYear;
            long rem = ageDays % daysPerYear;
            long months = rem / daysPerMonth;
            long days = rem % daysPerMonth;

            List<String> parts = new ArrayList<>(3);
            if (years > 0L) parts.add(years + " " + (years == 1L ? "year" : "years"));
            if (months > 0L) parts.add(months + " " + (months == 1L ? "month" : "months"));
            if (days > 0L || parts.isEmpty()) parts.add(days + " " + (days == 1L ? "day" : "days"));
            return String.join(", ", parts);
        } catch (Throwable ignored) {
            return formatDayAge(ageDays);
        }
    }

    private static String formatDayAge(long days) {
        long safe = Math.max(0L, days);
        return safe + " " + (safe == 1L ? "day" : "days");
    }

    private static long toLong(Object value) {
        try {
            if (value instanceof Number n) return n.longValue();
            if (value instanceof String s) return Long.parseLong(s);
        } catch (Throwable ignored) {}
        return 0L;
    }

    private static int toInt(Object value) {
        try {
            if (value instanceof Number n) return n.intValue();
            if (value instanceof String s) return Integer.parseInt(s);
        } catch (Throwable ignored) {}
        return 0;
    }
}
