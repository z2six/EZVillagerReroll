// neoforge\src\main\java\org\z2six\villageroverhaul\server\VillagerStatsEvents.java
package org.z2six.villageroverhaul.server;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class VillagerStatsEvents {

    private static volatile boolean registered = false;

    private VillagerStatsEvents() {}

    public static void register(IEventBus bus) {
        if (bus == null) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerStatsEvents.register called with null bus");
            return;
        }
        if (registered) return;
        registered = true;

        bus.addListener(VillagerStatsEvents::onEntityJoinLevel);
        VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerStatsEvents registered.");
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent e) {
        try {
            if (e == null) return;
            if (e.getLevel() == null) return;
            if (e.getLevel().isClientSide()) return;

            var entity = e.getEntity();
            if (entity == null) return;

            if (entity instanceof net.minecraft.world.entity.npc.Villager villager) {
                VillagerGenderService.ensureAssigned(villager);
            }

            // 1) Ensure NBT stat points exist
            VillagerStatsService.ensureStats(entity);
            VillagerAgeService.ensureBirthData(entity);

            // 2) Apply attribute modifiers (vitality/agility/strength/armor)
            VillagerCombatAttributeService.applyCombatModifiers(entity);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerStatsEvents.onEntityJoinLevel failed (soft): {}", t.toString());
        }
    }
}
