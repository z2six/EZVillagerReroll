// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ClientTradeLockCache.java
package org.z2six.ezvillagerreroll.network;

import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientTradeLockCache {

    private static final Map<Integer, Long> MASKS_BY_CONTAINER = new ConcurrentHashMap<>();

    private ClientTradeLockCache() {}

    public static void set(PacketTradeLocks msg) {
        try {
            if (msg == null) return;

            int cid = msg.containerId();
            long mask = msg.mask();

            if (cid < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] ClientTradeLockCache.set: ignoring cid<0 (cid={}, mask={})",
                        cid, Long.toUnsignedString(mask));
                return;
            }

            MASKS_BY_CONTAINER.put(cid, mask);
            EZVillagerReroll.LOG().info("[EZVR] ClientTradeLockCache set: containerId={} mask={}",
                    cid, Long.toUnsignedString(mask));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.set failed", t);
        }
    }

    public static long getMaskForContainer(int containerId) {
        try {
            if (containerId < 0) return 0L;
            return MASKS_BY_CONTAINER.getOrDefault(containerId, 0L);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.getMaskForContainer failed", t);
            return 0L;
        }
    }

    public static void clearContainer(int containerId) {
        try {
            if (containerId < 0) return;
            MASKS_BY_CONTAINER.remove(containerId);
            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache cleared: containerId={}", containerId);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.clearContainer failed", t);
        }
    }

    public static void clearAll() {
        try {
            int sz = MASKS_BY_CONTAINER.size();
            MASKS_BY_CONTAINER.clear();
            EZVillagerReroll.LOG().debug("[EZVR] ClientTradeLockCache cleared all ({} entr{}).",
                    sz, sz == 1 ? "y" : "ies");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientTradeLockCache.clearAll failed", t);
        }
    }
}
