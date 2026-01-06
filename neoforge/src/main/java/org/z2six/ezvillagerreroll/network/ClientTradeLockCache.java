// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ClientTradeLockCache.java
package org.z2six.ezvillagerreroll.network;

import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientTradeLockCache {

    private static final class Entry {
        long mask;
        long updatedAtMs;
    }

    private static final Map<Integer, Entry> MAP = new ConcurrentHashMap<>();

    public static void set(PacketTradeLocks pkt) {
        try {
            if (pkt == null) return;

            Entry e = MAP.computeIfAbsent(pkt.traderEntityId, k -> new Entry());
            e.mask = pkt.lockedMask;
            e.updatedAtMs = System.currentTimeMillis();

            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache set: traderId={} mask={}",
                    pkt.traderEntityId, Long.toUnsignedString(pkt.lockedMask));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.set failed", t);
        }
    }

    public static long getMask(int traderEntityId) {
        Entry e = MAP.get(traderEntityId);
        return e == null ? 0L : e.mask;
    }

    public static void clear(int traderEntityId) {
        MAP.remove(traderEntityId);
    }

    private ClientTradeLockCache() {}
}
