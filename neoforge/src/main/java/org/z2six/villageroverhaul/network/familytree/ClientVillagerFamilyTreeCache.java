package org.z2six.villageroverhaul.network.familytree;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientVillagerFamilyTreeCache {
    private static final Map<Integer, PacketVillagerFamilyTreeData> CACHE = new ConcurrentHashMap<>();

    private ClientVillagerFamilyTreeCache() {
    }

    public static void accept(PacketVillagerFamilyTreeData msg) {
        if (msg == null) {
            return;
        }
        CACHE.put(msg.villagerEntityId(), msg);
    }

    public static PacketVillagerFamilyTreeData get(int villagerEntityId) {
        return CACHE.get(villagerEntityId);
    }
}
