package org.z2six.villageroverhaul.client;

import org.z2six.villageroverhaul.farming.FarmingSettings;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsData;

import java.util.Map;
import java.util.WeakHashMap;

public final class ClientFarmingSettingsCache {

    private static final Map<Integer, FarmingSettings> SETTINGS = new WeakHashMap<>();
    private static final Map<Integer, Long> SETTINGS_AT = new WeakHashMap<>();

    private ClientFarmingSettingsCache() {}

    public static void set(PacketFarmingSettingsData msg) {
        try {
            if (msg == null) return;
            FarmingSettings incoming = FarmingSettings.fromTag(msg.settings());

            FarmingSettings cur = SETTINGS.get(msg.villagerEntityId());
            if (cur != null && incoming != null) {
                // Ignore stale out-of-order responses (query vs update race).
                if (incoming.updatedAt < cur.updatedAt) {
                    return;
                }
            }

            SETTINGS.put(msg.villagerEntityId(), incoming);
            SETTINGS_AT.put(msg.villagerEntityId(), System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    public static FarmingSettings get(int villagerEntityId) {
        try {
            return SETTINGS.get(villagerEntityId);
        } catch (Throwable t) {
            return null;
        }
    }

    public static long getAgeMs(int villagerEntityId) {
        try {
            Long at = SETTINGS_AT.get(villagerEntityId);
            return at == null ? Long.MAX_VALUE : (System.currentTimeMillis() - at);
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }
}
