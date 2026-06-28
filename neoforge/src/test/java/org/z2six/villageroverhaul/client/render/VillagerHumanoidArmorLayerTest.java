package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VillagerHumanoidArmorLayerTest {

    @Test
    void dwarfHeadArmorUsesDwarfHeadPivot() {
        ArmorHeadPivot pivot =
                DwarfArmorGeometry.headArmorPivotFor(ArmorEditorProfile.DWARF, ArmorEditorSettings.SlotKey.HEAD);

        assertEquals(new ArmorHeadPivot(0.0f, 2.0f, 0.0f), pivot);
    }

    @Test
    void regularVillagerHeadArmorKeepsVanillaHumanoidPivot() {
        ArmorHeadPivot pivot =
                DwarfArmorGeometry.headArmorPivotFor(ArmorEditorProfile.VILLAGER, ArmorEditorSettings.SlotKey.HEAD);

        assertEquals(ArmorHeadPivot.IDENTITY, pivot);
    }

    @Test
    void dwarfNonHeadArmorKeepsVanillaHumanoidPivot() {
        ArmorHeadPivot pivot =
                DwarfArmorGeometry.headArmorPivotFor(ArmorEditorProfile.DWARF, ArmorEditorSettings.SlotKey.CHEST);

        assertEquals(ArmorHeadPivot.IDENTITY, pivot);
    }

    @Test
    void dwarfArmorUsesDwarfLimbPivotsForAnimatedParts() {
        assertEquals(new ArmorPartPivot(-6.5f, 5.0f, 1.0f),
                DwarfArmorGeometry.limbArmorPivotFor(
                        ArmorEditorProfile.DWARF,
                        ArmorEditorSettings.SlotKey.CHEST,
                        ArmorEditorSettings.PartKey.RIGHT_ARM
                ));
        assertEquals(new ArmorPartPivot(6.5f, 5.0f, 1.0f),
                DwarfArmorGeometry.limbArmorPivotFor(
                        ArmorEditorProfile.DWARF,
                        ArmorEditorSettings.SlotKey.CHEST,
                        ArmorEditorSettings.PartKey.LEFT_ARM
                ));
        assertEquals(new ArmorPartPivot(-3.0f, 17.0f, 0.5f),
                DwarfArmorGeometry.limbArmorPivotFor(
                        ArmorEditorProfile.DWARF,
                        ArmorEditorSettings.SlotKey.LEGS,
                        ArmorEditorSettings.PartKey.RIGHT_LEG
                ));
        assertEquals(ArmorPartPivot.IDENTITY,
                DwarfArmorGeometry.limbArmorPivotFor(
                        ArmorEditorProfile.VILLAGER,
                        ArmorEditorSettings.SlotKey.CHEST,
                        ArmorEditorSettings.PartKey.RIGHT_ARM
                ));
    }

    @Test
    void dwarfChestArmorUsesAnimatedArmsWithoutPseudoArmFlag() {
        assertTrue(ArmorArmAnimationPolicy.shouldAnimateArmorArms(true, false));
        assertFalse(ArmorArmAnimationPolicy.shouldAnimateArmorArms(false, false));
        assertTrue(ArmorArmAnimationPolicy.shouldAnimateArmorArms(false, true));
    }
}
