package org.z2six.villageroverhaul.server.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerSeatServiceTest {

    @Test
    void passiveMacroStepsKeepVillagerSeated() {
        assertFalse(VillagerSeatPolicy.shouldDismountBeforeStepName("WAIT"));
        assertFalse(VillagerSeatPolicy.shouldDismountBeforeStepName("LOOK"));
    }

    @Test
    void activeMacroStepsDismountVillagerBeforeContinuing() {
        assertTrue(VillagerSeatPolicy.shouldDismountBeforeStepName("WAYPOINT"));
        assertTrue(VillagerSeatPolicy.shouldDismountBeforeStepName("INTERACT_BLOCK"));
        assertTrue(VillagerSeatPolicy.shouldDismountBeforeStepName("INTERACT_ENTITY"));
        assertTrue(VillagerSeatPolicy.shouldDismountBeforeStepName("WITHDRAW_CHEST"));
        assertTrue(VillagerSeatPolicy.shouldDismountBeforeStepName("DEPOSIT_CHEST"));
    }

    @Test
    void seatLikeEntityNamesAreDetectedWithoutSpecificModIds() {
        assertTrue(VillagerSeatPolicy.isLikelySeatEntityName("valhelsia_furniture:seat", "net.valhelsia.SeatEntity"));
        assertTrue(VillagerSeatPolicy.isLikelySeatEntityName("another_mod:oak_chair_mount", "com.example.HiddenMount"));
        assertTrue(VillagerSeatPolicy.isLikelySeatEntityName("other:sittable", "com.example.Entity"));
        assertFalse(VillagerSeatPolicy.isLikelySeatEntityName("minecraft:item", "net.minecraft.world.entity.item.ItemEntity"));
        assertFalse(VillagerSeatPolicy.isLikelySeatEntityName("minecraft:zombie", "net.minecraft.world.entity.monster.Zombie"));
    }

    @Test
    void onlyPassiveActivitiesKeepMacroSeatAfterInteraction() {
        assertTrue(VillagerSeatPolicy.shouldRemainSeatedForActivityName("IDLE", "OFF", false, false, false));
        assertTrue(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "OFF", false, false, false));

        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("FOLLOW", "OFF", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("PATROL_SETUP", "OFF", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("PATROL", "OFF", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("TRADING", "OFF", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "DEFEND", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "AGGRESSIVE", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "FLEE", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "HELP", false, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "OFF", true, false, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "OFF", false, true, false));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "OFF", false, false, true));
        assertFalse(VillagerSeatPolicy.shouldRemainSeatedForActivityName("NEUTRAL", "OFF", false, false, false, true));
    }
}
