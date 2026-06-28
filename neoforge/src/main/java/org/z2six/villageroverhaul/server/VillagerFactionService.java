package org.z2six.villageroverhaul.server;

import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.config.ServerConfig;

public final class VillagerFactionService {
    public static final byte FACTION_UNASSIGNED = -1;
    public static final byte FACTION_HUMAN = 0;
    public static final byte FACTION_DWARF = 1;

    private static volatile boolean registered = false;

    private VillagerFactionService() {}

    public static void register(IEventBus bus) {
        if (bus == null || registered) return;
        registered = true;
        bus.addListener(VillagerFactionService::onEntityJoinLevel);
        VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerFactionService registered.");
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent e) {
        try {
            if (e == null || e.getLevel() == null || e.getLevel().isClientSide()) return;
            if (!(e.getEntity() instanceof Villager vill)) return;
            ensureAssigned(vill);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerFactionService.onEntityJoinLevel failed (soft): {}", t.toString());
        }
    }

    public static void ensureAssigned(Villager vill) {
        try {
            if (!(vill instanceof VillagerOverhaulRenderAccess acc)) return;
            if (acc.ezvr$getFaction() != FACTION_UNASSIGNED) return;

            int chance = Math.max(0, Math.min(100, ServerConfig.dwarfVillagerChancePct));
            if (chance <= 0) {
                acc.ezvr$setFaction(FACTION_HUMAN);
                return;
            }

            boolean dwarf = chance >= 100 || vill.getRandom().nextInt(100) < chance;
            if (dwarf) {
                acc.ezvr$setFaction(FACTION_DWARF);
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Assigned dwarf faction to villager={}", vill.getUUID());
            } else {
                acc.ezvr$setFaction(FACTION_HUMAN);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerFactionService.ensureAssigned failed (soft): {}", t.toString());
        }
    }

    public static boolean isDwarf(Object entity) {
        try {
            return entity instanceof VillagerOverhaulRenderAccess acc && acc.ezvr$getFaction() == FACTION_DWARF;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
