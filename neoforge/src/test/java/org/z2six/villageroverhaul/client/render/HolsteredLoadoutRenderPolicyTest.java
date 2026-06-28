package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HolsteredLoadoutRenderPolicyTest {

    @Test
    void normalVillagerCustomArmsSuppressHolsters() {
        assertFalse(HolsteredLoadoutRenderPolicy.shouldRenderWhileHandsEmpty(false, true));
    }

    @Test
    void dwarfCustomArmFlagDoesNotSuppressHolsters() {
        assertTrue(HolsteredLoadoutRenderPolicy.shouldRenderWhileHandsEmpty(true, true));
    }

    @Test
    void dwarfHolstersUseSameFittedDefaultsForAllWaistProfiles() {
        HolsterWaistDefaults.Transform expected = new HolsterWaistDefaults.Transform(
                0.23000011f, 0.1000003f, 0.6199997f,
                -143.0f, 92.0f, 144.0f,
                1.0f,
                0.0f,
                "Z",
                30.0f,
                "Z"
        );

        assertEquals(expected, HolsterWaistDefaults.dwarfTransform(HolsterWaistDefaults.Profile.DEFAULT));
        assertEquals(expected, HolsterWaistDefaults.dwarfTransform(HolsterWaistDefaults.Profile.BOW));
        assertEquals(expected, HolsterWaistDefaults.dwarfTransform(HolsterWaistDefaults.Profile.CROSSBOW));
    }
}
