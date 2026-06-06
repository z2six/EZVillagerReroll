package org.z2six.villageroverhaul.server.ai;

import org.z2six.villageroverhaul.VillagerSeatCompat;

import java.util.Locale;

final class VillagerSeatPolicy {

    private VillagerSeatPolicy() {}

    static boolean shouldDismountBeforeStep(Object type) {
        if (type == null) return false;
        if (type instanceof Enum<?> e) return shouldDismountBeforeStepName(e.name());
        return shouldDismountBeforeStepName(String.valueOf(type));
    }

    static boolean shouldDismountBeforeStepName(String typeName) {
        if (typeName == null || typeName.isBlank()) return false;
        return !"WAIT".equals(typeName) && !"LOOK".equals(typeName);
    }

    static boolean shouldRemainSeatedForActivityName(String modeName,
                                                     String combatModeName,
                                                     boolean combatEngaged,
                                                     boolean storageActive,
                                                     boolean manualFarmingActive) {
        if (combatEngaged || storageActive || manualFarmingActive) return false;

        String combat = normalize(combatModeName);
        if (!combat.isEmpty() && !"OFF".equals(combat)) return false;

        String mode = normalize(modeName);
        return "IDLE".equals(mode) || "NEUTRAL".equals(mode);
    }

    static boolean isLikelySeatEntityName(String entityId, String className) {
        return VillagerSeatCompat.isLikelySeatEntityName(entityId, className);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
