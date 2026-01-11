// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/ClientRerollCooldownCache.java
package org.z2six.villageroverhaul.client;

import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.PacketRerollCooldownState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cooldown cache keyed by merchant containerId.
 *
 * We store:
 * - cooldown end timestamp (ms) for remaining calculations
 * - last known server-configured cooldown ticks (informational, for tooltip when not cooling down)
 *
 * Server remains authoritative; client uses this for UI responsiveness and display.
 */
public final class ClientRerollCooldownCache {

    // containerId -> cooldownEndMs (only present while cooling down)
    private static final Map<Integer, Long> COOLDOWN_END_MS = new ConcurrentHashMap<>();

    // containerId -> last known configured cooldown ticks (keep even when not cooling down)
    private static final Map<Integer, Integer> LAST_CFG_TICKS = new ConcurrentHashMap<>();

    private ClientRerollCooldownCache() {}

    public static void apply(PacketRerollCooldownState msg) {
        try {
            if (msg == null) return;

            int cid = Math.max(0, msg.containerId());
            int remTicks = Math.max(0, msg.ticksRemaining());
            int cfgTicks = Math.max(0, msg.cooldownTicksConfigured());

            // Always remember cfg ticks if provided (even if rem=0).
            // This fixes "Cooldown: ?" while active.
            if (cfgTicks > 0) {
                LAST_CFG_TICKS.put(cid, cfgTicks);
            }

            if (remTicks <= 0) {
                COOLDOWN_END_MS.remove(cid);
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CooldownCache: clear end (containerId={}, cfgTicks={})", cid, cfgTicks);
                return;
            }

            long now = System.currentTimeMillis();
            long end = now + (remTicks * 50L);

            COOLDOWN_END_MS.put(cid, end);

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] CooldownCache: set (containerId={} ticksRemaining={} cfgTicks={} endMs={})",
                    cid, remTicks, cfgTicks, end
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CooldownCache.apply failed (soft): {}", t.toString());
        }
    }

    public static void clearContainer(int containerId) {
        try {
            COOLDOWN_END_MS.remove(containerId);
            LAST_CFG_TICKS.remove(containerId);
        } catch (Throwable ignored) {}
    }

    public static void clearAll() {
        try {
            COOLDOWN_END_MS.clear();
            LAST_CFG_TICKS.clear();
        } catch (Throwable ignored) {}
    }

    /** Returns 0 if no cooldown or expired. */
    public static int getRemainingTicks(int containerId) {
        try {
            Long end = COOLDOWN_END_MS.get(containerId);
            if (end == null) return 0;

            long now = System.currentTimeMillis();
            long diff = end - now;
            if (diff <= 0) {
                COOLDOWN_END_MS.remove(containerId);
                return 0;
            }

            // ceil(diff / 50)
            long ticks = (diff + 49L) / 50L;
            if (ticks < 0) ticks = 0;
            if (ticks > Integer.MAX_VALUE) ticks = Integer.MAX_VALUE;
            return (int) ticks;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean isCoolingDown(int containerId) {
        return getRemainingTicks(containerId) > 0;
    }

    /**
     * Returns last known server-configured cooldown ticks for this container.
     * 0 means "unknown".
     */
    public static int getLastKnownTotalCooldownTicks(int containerId) {
        try {
            Integer v = LAST_CFG_TICKS.get(containerId);
            return v == null ? 0 : Math.max(0, v);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Optimistic local block: used immediately after a click so the UI disables instantly even before server reply.
     * This never grants permission; it only temporarily disables.
     */
    public static void setOptimisticCooldown(int containerId, int ticks) {
        try {
            int t = Math.max(0, ticks);
            if (t <= 0) return;

            long now = System.currentTimeMillis();
            long end = now + (t * 50L);

            Long prev = COOLDOWN_END_MS.get(containerId);
            if (prev == null || end > prev) {
                COOLDOWN_END_MS.put(containerId, end);
            }

            // Also store as last-known cfg ticks so active tooltip can show it even before server responds.
            Integer prevCfg = LAST_CFG_TICKS.get(containerId);
            if (prevCfg == null || t > prevCfg) {
                LAST_CFG_TICKS.put(containerId, t);
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CooldownCache: optimistic set (containerId={} ticks={})", containerId, t);
        } catch (Throwable ignored) {}
    }
}
