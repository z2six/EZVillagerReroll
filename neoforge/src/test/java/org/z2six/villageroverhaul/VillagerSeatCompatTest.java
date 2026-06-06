package org.z2six.villageroverhaul;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerSeatCompatTest {

    @Test
    void likelySeatNamesAreDetectedWithoutModSpecificIds() {
        assertTrue(VillagerSeatCompat.isLikelySeatEntityName("valhelsia_furniture:seat", "net.valhelsia.SeatEntity"));
        assertTrue(VillagerSeatCompat.isLikelySeatEntityName("some_mod:oak_chair_mount", "com.example.HiddenMount"));
        assertTrue(VillagerSeatCompat.isLikelySeatEntityName("other:sittable", "com.example.Entity"));

        assertFalse(VillagerSeatCompat.isLikelySeatEntityName("minecraft:horse", "net.minecraft.world.entity.animal.horse.Horse"));
        assertFalse(VillagerSeatCompat.isLikelySeatEntityName("minecraft:item", "net.minecraft.world.entity.item.ItemEntity"));
    }

    @Test
    void seatAttachmentUsesAtLeastPlayerVehicleBaseline() {
        assertEquals(0.6D, VillagerSeatCompat.adjustSeatVehicleAttachmentY(0.0D));
        assertEquals(0.6D, VillagerSeatCompat.adjustSeatVehicleAttachmentY(0.4D));
        assertEquals(0.8D, VillagerSeatCompat.adjustSeatVehicleAttachmentY(0.8D));
    }
}
