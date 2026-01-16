package org.z2six.villageroverhaul.network;

import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache for villager stats snapshots.
 *
 * This class is deliberately "common-safe" (no net.minecraft.client imports) so Network.java
 * can update it directly without client-only classloading hazards.
 */
public final class ClientVillagerStatsCache {

    private static final class Entry {
        final PacketVillagerStatsData data;
        final long timeMs;

        Entry(PacketVillagerStatsData data, long timeMs) {
            this.data = data;
            this.timeMs = timeMs;
        }
    }

    private static final Map<Integer, Entry> MAP = new ConcurrentHashMap<>();

    private ClientVillagerStatsCache() {}

    public static void accept(PacketVillagerStatsData data) {
        try {
            if (data == null) return;
            MAP.put(data.villagerEntityId(), new Entry(data, System.currentTimeMillis()));

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] ClientVillagerStatsCache.accept: entityId={} ok={} g={} t={} i={} h={} vit={} agi={} str={} arm={}",
                    data.villagerEntityId(), data.ok(),
                    data.generosity(), data.timeliness(), data.intellect(), data.hoarder(),
                    data.vitality(), data.agility(), data.strength(), data.armor()
            );
        } catch (Throwable t) {
            // soft
        }
    }

    public static PacketVillagerStatsData get(int entityId) {
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
        try {
            MAP.remove(entityId);
        } catch (Throwable ignored) {}
    }

    public static void clearAll() {
        try {
            MAP.clear();
        } catch (Throwable ignored) {}
    }
}
