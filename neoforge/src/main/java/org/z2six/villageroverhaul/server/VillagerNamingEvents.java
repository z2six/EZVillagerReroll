package org.z2six.villageroverhaul.server;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import org.jetbrains.annotations.Nullable;

public final class VillagerNamingEvents {
    private static volatile boolean registered = false;
    private static volatile boolean renameSingleNamedVillagersEnabled = false;

    private VillagerNamingEvents() {
    }

    public static void register(IEventBus bus) {
        if (bus == null || registered) {
            return;
        }

        registered = true;
        bus.addListener(VillagerNamingEvents::onFinalizeSpawn);
        bus.addListener(VillagerNamingEvents::onEntityJoinLevel);
    }

    public static boolean toggleRenameSingleNamedVillagers() {
        renameSingleNamedVillagersEnabled = !renameSingleNamedVillagersEnabled;
        return renameSingleNamedVillagersEnabled;
    }

    public static boolean isRenameSingleNamedVillagersEnabled() {
        return renameSingleNamedVillagersEnabled;
    }

    static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            renameVillagerIfNeeded(villager);
        }
    }

    static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !event.loadedFromDisk()) {
            return;
        }

        if (event.getEntity() instanceof Villager villager) {
            renameVillagerIfNeeded(villager);
        }
    }

    static boolean renameVillagerIfNeeded(Villager villager) {
        VillagerNameStateService.tryAdoptExistingName(villager);

        if (!needsGeneratedName(villager)) {
            return false;
        }

        if (villager.isBaby() && VillagerFamilyNames.inheritSurnameFromNearbyAdults(villager)) {
            return true;
        }

        VillagerNameStateService.assignGeneratedName(villager);
        return true;
    }

    private static boolean needsGeneratedName(Villager villager) {
        @Nullable Component customName = villager.getCustomName();
        if (customName == null) {
            return true;
        }
        if (!renameSingleNamedVillagersEnabled) {
            return false;
        }
        return VillagerNameGenerator.extractLastName(customName.getString()) == null;
    }
}
