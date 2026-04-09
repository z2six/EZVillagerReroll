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
        VillagerGenderService.ensureAssigned(child);
        VillagerGenderService.ensureAssigned(parentA);
        VillagerGenderService.ensureAssigned(parentB);

        VillagerNameStateService.tryAdoptExistingName(parentA);
        VillagerNameStateService.tryAdoptExistingName(parentB);
        if (!VillagerNameStateService.isTracked(parentA) || !VillagerNameStateService.isTracked(parentB)) {
            return false;
        }

        Villager maleParent = VillagerGenderService.resolveMaleParent(parentA, parentB);
        Villager femaleParent = VillagerGenderService.resolveFemaleParent(parentA, parentB);
        if (maleParent == null || femaleParent == null) {
            return false;
        }

        String inheritedSurname = pickSurname(maleParent, femaleParent);
        if (inheritedSurname == null) {
            return false;
        }

        VillagerNameStateService.assignGeneratedName(child, inheritedSurname);
        VillagerStatsService.inheritStatsFromParents(child, maleParent, femaleParent);
        VillagerFamilyTreeService.recordBreeding(child, maleParent, femaleParent);
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
    private static String pickSurname(Villager maleParent, Villager femaleParent) {
        String maleSurname = extractSurname(maleParent);
        if (maleSurname != null) {
            return maleSurname;
        }
        return extractSurname(femaleParent);
    }

    @Nullable
    private static String extractSurname(Villager villager) {
        return VillagerNameStateService.getTrackedLastName(villager);
    }
}
