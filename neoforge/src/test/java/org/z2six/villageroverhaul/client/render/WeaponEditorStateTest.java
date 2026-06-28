package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WeaponEditorStateTest {
    @Test
    void heldProfilesAreIndependentPerModelAndItemProfile() {
        try {
            WeaponEditorState.resetAllHeld();
            WeaponEditorTransform villagerShield = new WeaponEditorTransform(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 1.1f, 1.2f, 1.3f);

            WeaponEditorState.setHeldTransform(ArmorEditorProfile.VILLAGER, WeaponEditorState.HeldProfile.SHIELD, villagerShield);

            assertEquals(villagerShield, WeaponEditorState.heldTransform(ArmorEditorProfile.VILLAGER, WeaponEditorState.HeldProfile.SHIELD));
            assertEquals(WeaponEditorState.defaultHeldTransform(WeaponEditorState.HeldProfile.SHIELD),
                    WeaponEditorState.heldTransform(ArmorEditorProfile.DWARF, WeaponEditorState.HeldProfile.SHIELD));
            assertEquals(WeaponEditorState.defaultHeldTransform(WeaponEditorState.HeldProfile.GENERIC),
                    WeaponEditorState.heldTransform(ArmorEditorProfile.VILLAGER, WeaponEditorState.HeldProfile.GENERIC));
        } finally {
            WeaponEditorState.resetAllHeld();
        }
    }

    @Test
    void heldParamUpdatesOnlyRequestedAxis() {
        try {
            WeaponEditorState.resetAllHeld();

            WeaponEditorState.setHeldParam(ArmorEditorProfile.DWARF, WeaponEditorState.HeldProfile.BOW, "sx", 0.25f, true);
            WeaponEditorState.setHeldParam(ArmorEditorProfile.DWARF, WeaponEditorState.HeldProfile.BOW, "ty", 0.5f, false);

            WeaponEditorTransform tx = WeaponEditorState.heldTransform(ArmorEditorProfile.DWARF, WeaponEditorState.HeldProfile.BOW);
            assertEquals(1.25f, tx.sx(), 0.0001f);
            assertEquals(0.5f, tx.ty(), 0.0001f);
            assertEquals(-0.05f, tx.tz(), 0.0001f);
        } finally {
            WeaponEditorState.resetAllHeld();
        }
    }
}
