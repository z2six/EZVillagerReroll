// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ClientTradeLockCache.java
package org.z2six.ezvillagerreroll.network;

import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of trade lock bitmasks per "trader id".
 *
 * We intentionally do NOT assume PacketTradeLocks is a record.
 * Different implementations may expose fields or getters with different names.
 * This cache resolves the values via reflection to avoid compile/mapping brittleness.
 */
public final class ClientTradeLockCache {

    private static final Map<Integer, Long> MASKS = new ConcurrentHashMap<>();

    private ClientTradeLockCache() {}

    public static void set(PacketTradeLocks msg) {
        try {
            if (msg == null) return;

            int traderId = extractTraderId(msg);
            long mask = extractMask(msg);

            if (traderId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] ClientTradeLockCache.set: could not extract trader id from {}",
                        msg.getClass().getName());
                return;
            }

            MASKS.put(traderId, mask);

            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache set: traderId={} mask={}",
                    traderId, Long.toUnsignedString(mask));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.set failed", t);
        }
    }

    public static long getMask(int traderEntityId) {
        try {
            if (traderEntityId < 0) return 0L;
            return MASKS.getOrDefault(traderEntityId, 0L);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.getMask failed", t);
            return 0L;
        }
    }

    public static void clear(int traderEntityId) {
        try {
            if (traderEntityId < 0) return;
            MASKS.remove(traderEntityId);
            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache cleared: traderId={}", traderEntityId);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.clear failed", t);
        }
    }

    /**
     * Clears all cached lock masks.
     * Useful when the client cannot reliably resolve a trader entity id for the current merchant.
     */
    public static void clearAll() {
        try {
            int sz = MASKS.size();
            MASKS.clear();
            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache cleared all ({} entr{}).",
                    sz, sz == 1 ? "y" : "ies");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.clearAll failed", t);
        }
    }

    // ---------------------------
    // Reflection helpers
    // ---------------------------

    private static int extractTraderId(Object msg) {
        // Try methods first
        Integer byMethod = invokeIntGetter(msg,
                "traderEntityId", "getTraderEntityId",
                "traderId", "getTraderId",
                "entityId", "getEntityId",
                "id", "getId"
        );
        if (byMethod != null) return byMethod;

        // Then fields
        Integer byField = readIntField(msg,
                "traderEntityId",
                "traderId",
                "entityId",
                "id"
        );
        return byField != null ? byField : -1;
    }

    private static long extractMask(Object msg) {
        // Try methods first
        Long byMethod = invokeLongGetter(msg,
                "mask", "getMask",
                "lockedMask", "getLockedMask",
                "lockMask", "getLockMask"
        );
        if (byMethod != null) return byMethod;

        // Then fields
        Long byField = readLongField(msg,
                "mask",
                "lockedMask",
                "lockMask"
        );
        return byField != null ? byField : 0L;
    }

    private static Integer invokeIntGetter(Object target, String... names) {
        for (String n : names) {
            try {
                Method m;
                try {
                    m = target.getClass().getMethod(n);
                } catch (NoSuchMethodException ignored) {
                    m = target.getClass().getDeclaredMethod(n);
                }
                m.setAccessible(true);
                Object r = m.invoke(target);
                if (r instanceof Integer i) return i;
                if (r instanceof Number num) return num.intValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Long invokeLongGetter(Object target, String... names) {
        for (String n : names) {
            try {
                Method m;
                try {
                    m = target.getClass().getMethod(n);
                } catch (NoSuchMethodException ignored) {
                    m = target.getClass().getDeclaredMethod(n);
                }
                m.setAccessible(true);
                Object r = m.invoke(target);
                if (r instanceof Long l) return l;
                if (r instanceof Number num) return num.longValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Integer readIntField(Object target, String... names) {
        for (String n : names) {
            try {
                Field f;
                try {
                    f = target.getClass().getField(n);
                } catch (NoSuchFieldException ignored) {
                    f = target.getClass().getDeclaredField(n);
                }
                f.setAccessible(true);
                Object r = f.get(target);
                if (r instanceof Integer i) return i;
                if (r instanceof Number num) return num.intValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Long readLongField(Object target, String... names) {
        for (String n : names) {
            try {
                Field f;
                try {
                    f = target.getClass().getField(n);
                } catch (NoSuchFieldException ignored) {
                    f = target.getClass().getDeclaredField(n);
                }
                f.setAccessible(true);
                Object r = f.get(target);
                if (r instanceof Long l) return l;
                if (r instanceof Number num) return num.longValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
