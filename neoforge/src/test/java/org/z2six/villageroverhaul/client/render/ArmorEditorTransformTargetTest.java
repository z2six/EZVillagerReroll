package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

final class ArmorEditorTransformTargetTest {

    @Test
    void chestTargetsExposeSlotBothArmsAndIndividualArms() {
        List<ArmorEditorTransformTarget> targets = ArmorEditorTransformTarget.targetsForSlot(ArmorEditorSettings.SlotKey.CHEST);

        assertEquals(List.of(
                ArmorEditorTransformTarget.slot(),
                ArmorEditorTransformTarget.arms(),
                ArmorEditorTransformTarget.rightArm(),
                ArmorEditorTransformTarget.leftArm()
        ), targets);
    }

    @Test
    void legsTargetsExposeSlotBothLegsAndIndividualLegs() {
        List<ArmorEditorTransformTarget> targets = ArmorEditorTransformTarget.targetsForSlot(ArmorEditorSettings.SlotKey.LEGS);

        assertEquals(List.of(
                ArmorEditorTransformTarget.slot(),
                ArmorEditorTransformTarget.legs(),
                ArmorEditorTransformTarget.rightLeg(),
                ArmorEditorTransformTarget.leftLeg()
        ), targets);
    }

    @Test
    void feetTargetsExposeSlotBothFeetAndIndividualLegs() {
        List<ArmorEditorTransformTarget> targets = ArmorEditorTransformTarget.targetsForSlot(ArmorEditorSettings.SlotKey.FEET);

        assertEquals(List.of(
                ArmorEditorTransformTarget.slot(),
                ArmorEditorTransformTarget.feet(),
                ArmorEditorTransformTarget.rightLeg(),
                ArmorEditorTransformTarget.leftLeg()
        ), targets);
    }

    @Test
    void unsupportedArmsSelectionFallsBackToSlotForNewSlot() {
        assertEquals(ArmorEditorTransformTarget.slot(),
                ArmorEditorTransformTarget.normalizeForSlot(ArmorEditorSettings.SlotKey.HEAD, ArmorEditorTransformTarget.arms()));
    }

    @Test
    void labelsAreShortEnoughForEditorButtons() {
        assertEquals("Slot", ArmorEditorTransformTarget.slot().label());
        assertEquals("Arms", ArmorEditorTransformTarget.arms().label());
        assertEquals("R Arm", ArmorEditorTransformTarget.rightArm().label());
        assertEquals("L Arm", ArmorEditorTransformTarget.leftArm().label());
        assertEquals("Legs", ArmorEditorTransformTarget.legs().label());
        assertEquals("Feet", ArmorEditorTransformTarget.feet().label());
        assertEquals("R Leg", ArmorEditorTransformTarget.rightLeg().label());
        assertEquals("L Leg", ArmorEditorTransformTarget.leftLeg().label());
    }
}
