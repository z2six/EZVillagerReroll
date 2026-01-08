// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/logic/RerollState.java
package org.z2six.ezvillagerreroll.logic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RerollState {

    private static final Map<UUID, Long> lastTick = new HashMap<>();
    private static final Map<UUID, Integer> dailyCount = new HashMap<>();
    private static long lastDay = Long.MIN_VALUE;

    public static boolean canReroll(ServerPlayer sp, Villager vill) {
        if (sp == null || vill == null) return false;
        return canReroll(sp.serverLevel(), vill);
    }

    public static boolean canReroll(ServerLevel lvl, Villager vill) {
        if (lvl == null || vill == null) return false;

        long now = lvl.getGameTime();
        long day = lvl.getDayTime() / 24000L;

        if (day != lastDay) {
            dailyCount.clear();
            lastDay = day;
        }

        int cooldown = ServerConfig.cooldownTicks;
        if (cooldown > 0) {
            long last = lastTick.getOrDefault(vill.getUUID(), Long.MIN_VALUE);
            if (last != Long.MIN_VALUE && now - last < cooldown) return false;
        }

        int cap = ServerConfig.perVillagerDaily;
        if (cap > 0) {
            int used = dailyCount.getOrDefault(vill.getUUID(), 0);
            if (used >= cap) return false;
        }

        return true;
    }

    public static void markRerolled(ServerPlayer sp, Villager vill) {
        if (sp == null || vill == null) return;
        markRerolled(sp.serverLevel(), vill);
    }

    public static void markRerolled(ServerLevel lvl, Villager vill) {
        if (lvl == null || vill == null) return;

        long now = lvl.getGameTime();
        UUID id = vill.getUUID();
        lastTick.put(id, now);
        if (ServerConfig.perVillagerDaily > 0) {
            dailyCount.merge(id, 1, Integer::sum);
        }
    }

    /**
     * Returns how many ticks remain until cooldown is finished for this villager.
     * 0 means "no cooldown currently active" OR "cooldown disabled".
     *
     * This is used for server->client UI disabling; server remains authoritative.
     */
    public static int cooldownRemainingTicks(ServerLevel lvl, Villager vill) {
        try {
            if (lvl == null || vill == null) return 0;

            int cooldown = ServerConfig.cooldownTicks;
            if (cooldown <= 0) return 0;

            long now = lvl.getGameTime();
            long last = lastTick.getOrDefault(vill.getUUID(), Long.MIN_VALUE);
            if (last == Long.MIN_VALUE) return 0;

            long elapsed = now - last;
            if (elapsed >= cooldown) return 0;

            long rem = cooldown - elapsed;
            if (rem < 0) rem = 0;
            if (rem > Integer.MAX_VALUE) rem = Integer.MAX_VALUE;
            return (int) rem;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] cooldownRemainingTicks failed (soft): {}", t.toString());
            return 0;
        }
    }

    private RerollState() {}
}
