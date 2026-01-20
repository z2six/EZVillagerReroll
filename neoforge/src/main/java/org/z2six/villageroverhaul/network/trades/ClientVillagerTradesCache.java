// neoforge/src/main/java/org/z2six/villageroverhaul/network/trades/ClientVillagerTradesCache.java
package org.z2six.villageroverhaul.network.trades;

import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache for server-provided trade snapshots (results + lock mask).
 *
 * Common-safe (no net.minecraft.client refs) so Network.java can update it directly.
 */
public final class ClientVillagerTradesCache {

    private static final class Entry {
        final PacketVillagerTradesData data;
        final long timeMs;

        Entry(PacketVillagerTradesData data, long timeMs) {
            this.data = data;
            this.timeMs = timeMs;
        }
    }

    private static final Map<Integer, Entry> MAP = new ConcurrentHashMap<>();

    private ClientVillagerTradesCache() {}

    public static void accept(PacketVillagerTradesData data) {
        try {
            if (data == null) return;
            MAP.put(data.villagerEntityId(), new Entry(data, System.currentTimeMillis()));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ClientVillagerTradesCache.accept: entityId={} ok={} results={} mask={}",
                    data.villagerEntityId(), data.ok(),
                    data.results() == null ? 0 : data.results().size(),
                    Long.toUnsignedString(data.lockMask()));
        } catch (Throwable ignored) {}
    }

    public static PacketVillagerTradesData get(int entityId) {
        try {
            Entry e = MAP.get(entityId);
            return e == null ? null : e.data;
        } catch (Throwable t) {
            return null;
        }
    }

    public static long ageMs(int entityId) {
        try {
            Entry e = MAP.get(entityId);
            if (e == null) return Long.MAX_VALUE;
            long now = System.currentTimeMillis();
            long age = now - e.timeMs;
            return age < 0 ? 0 : age;
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    public static void clear(int entityId) {
        try { MAP.remove(entityId); } catch (Throwable ignored) {}
    }
}

