// neoforge\src\main\java\org\z2six\villageroverhaul\client\ClientCombatSettingsCache.java
package org.z2six.villageroverhaul.client;

import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsData;

import java.util.Map;
import java.util.WeakHashMap;

public final class ClientCombatSettingsCache {

    private static final Map<Integer, CombatSettings> SETTINGS = new WeakHashMap<>();
    private static final Map<Integer, Long> SETTINGS_AT = new WeakHashMap<>();

    private ClientCombatSettingsCache() {}

    public static void set(PacketCombatSettingsData msg) {
        try {
            if (msg == null) return;
            int id = msg.global() ? 0 : msg.villagerEntityId();
            CombatSettings settings = CombatSettings.fromTag(msg.settings());
            SETTINGS.put(id, settings);
            SETTINGS_AT.put(id, System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    public static CombatSettings get(int villagerEntityId, boolean global) {
        try {
            int id = global ? 0 : villagerEntityId;
            return SETTINGS.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    public static long getAgeMs(int villagerEntityId, boolean global) {
        try {
            int id = global ? 0 : villagerEntityId;
            Long at = SETTINGS_AT.get(id);
            return at == null ? Long.MAX_VALUE : (System.currentTimeMillis() - at);
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }
}
