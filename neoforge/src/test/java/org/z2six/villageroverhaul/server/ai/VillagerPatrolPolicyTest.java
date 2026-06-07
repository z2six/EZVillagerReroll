package org.z2six.villageroverhaul.server.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerPatrolPolicyTest {

    @Test
    void oneWaypointIsAValidStationaryPatrolRoute() {
        assertFalse(VillagerPatrolPolicy.hasUsableWaypoints(0));
        assertTrue(VillagerPatrolPolicy.hasUsableWaypoints(1));
        assertTrue(VillagerPatrolPolicy.hasMovementWaypoints(2));
    }

    @Test
    void currentSetupSaveDoesNotLegacyMigrateTheInProgressRoute() {
        assertTrue(VillagerPatrolPolicy.shouldMigrateLegacySingleRoute(false, true, 2, false));
        assertFalse(VillagerPatrolPolicy.shouldMigrateLegacySingleRoute(false, true, 2, true));
    }
}
