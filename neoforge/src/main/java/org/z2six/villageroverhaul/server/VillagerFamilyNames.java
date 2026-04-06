package org.z2six.villageroverhaul.server;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

public final class VillagerFamilyNames {
    private static final double NEARBY_ADULT_SEARCH_RADIUS = 12.0D;

    private VillagerFamilyNames() {
    }

    public static boolean inheritSurnameFromParents(Villager child, Villager parentA, Villager parentB) {
        VillagerNameStateService.tryAdoptExistingName(parentA);
        VillagerNameStateService.tryAdoptExistingName(parentB);
        if (!VillagerNameStateService.isTracked(parentA) || !VillagerNameStateService.isTracked(parentB)) {
            return false;
        }

        String inheritedSurname = pickSurname(child, parentA, parentB);
        if (inheritedSurname == null) {
            return false;
        }

        VillagerNameStateService.assignGeneratedName(child, inheritedSurname);
        VillagerStatsService.inheritStatsFromParents(child, parentA, parentB);
        VillagerFamilyTreeService.recordBreeding(child, parentA, parentB);
        return true;
    }

    public static boolean inheritSurnameFromNearbyAdults(Villager child) {
        if (!(child.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        List<Villager> nearbyAdults = serverLevel.getEntitiesOfClass(
                Villager.class,
                child.getBoundingBox().inflate(NEARBY_ADULT_SEARCH_RADIUS),
                candidate -> candidate != child
                        && candidate.isAlive()
                        && !candidate.isBaby()
                        && (VillagerNameStateService.isTracked(candidate) || VillagerNameStateService.tryAdoptExistingName(candidate))
        );
        if (nearbyAdults.isEmpty()) {
            return false;
        }

        Villager selectedAdult = nearbyAdults.get(child.getRandom().nextInt(nearbyAdults.size()));
        String inheritedSurname = extractSurname(selectedAdult);
        if (inheritedSurname == null) {
            return false;
        }

        VillagerNameStateService.assignGeneratedName(child, inheritedSurname);
        return true;
    }

    @Nullable
    private static String pickSurname(Villager child, Villager parentA, Villager parentB) {
        String surnameA = extractSurname(parentA);
        String surnameB = extractSurname(parentB);

        if (surnameA == null) {
            return surnameB;
        }
        if (surnameB == null) {
            return surnameA;
        }
        return child.getRandom().nextBoolean() ? surnameA : surnameB;
    }

    @Nullable
    private static String extractSurname(Villager villager) {
        return VillagerNameStateService.getTrackedLastName(villager);
    }
}
