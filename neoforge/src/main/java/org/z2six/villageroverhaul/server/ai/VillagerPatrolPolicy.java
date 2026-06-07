package org.z2six.villageroverhaul.server.ai;

final class VillagerPatrolPolicy {
    private VillagerPatrolPolicy() {}

    static boolean hasUsableWaypoints(int waypointCount) {
        return waypointCount >= 1;
    }

    static boolean hasMovementWaypoints(int waypointCount) {
        return waypointCount >= 2;
    }

    static boolean shouldMigrateLegacySingleRoute(boolean hasSavedRouteList, boolean finalized, int waypointCount, boolean savingCurrentSetup) {
        return !savingCurrentSetup
                && !hasSavedRouteList
                && finalized
                && hasUsableWaypoints(waypointCount);
    }
}
