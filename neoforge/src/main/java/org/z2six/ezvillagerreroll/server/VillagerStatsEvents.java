// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/VillagerStatsEvents.java
package org.z2six.ezvillagerreroll.server;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class VillagerStatsEvents {

    private static volatile boolean registered = false;

    private VillagerStatsEvents() {}

    public static void register(IEventBus bus) {
        if (bus == null) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerStatsEvents.register called with null bus");
            return;
        }
        if (registered) return;
        registered = true;

        bus.addListener(VillagerStatsEvents::onEntityJoinLevel);
        EZVillagerReroll.LOG().info("[EZVR] VillagerStatsEvents registered.");
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent e) {
        try {
            if (e == null) return;
            if (e.getLevel() == null) return;
            if (e.getLevel().isClientSide()) return;

            var entity = e.getEntity();
            if (entity == null) return;

            // Assign stats to any supported merchant/villager-like entity.
            VillagerStatsService.ensureStats(entity);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] VillagerStatsEvents.onEntityJoinLevel failed (soft): {}", t.toString());
        }
    }
}
