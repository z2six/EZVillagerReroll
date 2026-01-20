// neoforge\src\main\java\org\z2six\villageroverhaul\network\ClientVillagerAttributesCache.java
package org.z2six.villageroverhaul.network;

import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.attrs.PacketVillagerAttributesData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache for server-provided attribute snapshots.
 *
 * Common-safe (no net.minecraft.client refs) so Network.java can update it directly.
 */
public final class ClientVillagerAttributesCache {

    private static final class Entry {
        final PacketVillagerAttributesData data;
        final long timeMs;

        Entry(PacketVillagerAttributesData data, long timeMs) {
            this.data = data;
            this.timeMs = timeMs;
        }
    }

    private static final Map<Integer, Entry> MAP = new ConcurrentHashMap<>();

    private ClientVillagerAttributesCache() {}

    public static void accept(PacketVillagerAttributesData data) {
        try {
            if (data == null) return;
            MAP.put(data.villagerEntityId(), new Entry(data, System.currentTimeMillis()));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ClientVillagerAttributesCache.accept: entityId={} ok={} entries={}",
                    data.villagerEntityId(), data.ok(), data.entries() == null ? 0 : data.entries().size());
        } catch (Throwable ignored) {}
    }

    public static PacketVillagerAttributesData get(int entityId) {
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

