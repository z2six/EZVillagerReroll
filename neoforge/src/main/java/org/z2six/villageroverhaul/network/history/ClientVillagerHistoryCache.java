// neoforge/src/main/java/org/z2six/villageroverhaul/network/history/ClientVillagerHistoryCache.java
package org.z2six.villageroverhaul.network.history;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of server history snapshots per entityId.
 */
public final class ClientVillagerHistoryCache {
    private static final Map<Integer, PacketVillagerHistoryData> CACHE = new ConcurrentHashMap<>();

    private ClientVillagerHistoryCache() {}

    public static void accept(PacketVillagerHistoryData msg) {
        if (msg == null) return;
        CACHE.put(msg.villagerEntityId(), msg);
    }

    public static PacketVillagerHistoryData get(int villagerEntityId) {
        return CACHE.get(villagerEntityId);
    }
}

