// VillagerCombatInventoryProbeEvents.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerCombatInventoryProbeEvents.java
package org.z2six.villageroverhaul.server;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

/**
 * Separate event hook for probing what inventory/equipment a villager already has.
 * Kept separate from VillagerStatsEvents intentionally.
 */
public final class VillagerCombatInventoryProbeEvents {

    private static volatile boolean registered = false;

    private VillagerCombatInventoryProbeEvents() {}

    public static void register(IEventBus bus) {
        if (bus == null) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatInventoryProbeEvents.register called with null bus");
            return;
        }
        if (registered) return;
        registered = true;

        bus.addListener(VillagerCombatInventoryProbeEvents::onEntityJoinLevel);
        VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatInventoryProbeEvents registered.");
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent e) {
        try {
            if (e == null) return;
            if (e.getLevel() == null) return;
            if (e.getLevel().isClientSide()) return;

            var entity = e.getEntity();
            if (entity == null) return;

            VillagerCombatInventoryProbe.inspectAndPrepareIfNeeded(entity);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatInventoryProbeEvents.onEntityJoinLevel failed (soft): {}", t.toString());
        }
    }
}
