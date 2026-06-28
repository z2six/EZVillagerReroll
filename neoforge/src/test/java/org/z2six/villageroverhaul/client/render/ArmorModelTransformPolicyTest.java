package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ArmorModelTransformPolicyTest {

    @Test
    void customArmorModelsPreserveTheirNativeHeadPivot() {
        assertTrue(ArmorModelTransformPolicy.preserveNativeHeadPivot(ArmorModelTransformPolicy.RenderKind.EXTENDED_PIECES));
        assertTrue(ArmorModelTransformPolicy.preserveNativeHeadPivot(ArmorModelTransformPolicy.RenderKind.CUSTOM_HUMANOID));
        assertTrue(ArmorModelTransformPolicy.preserveNativeHeadPivot(ArmorModelTransformPolicy.RenderKind.CUSTOM_MODEL));
        assertFalse(ArmorModelTransformPolicy.preserveNativeHeadPivot(ArmorModelTransformPolicy.RenderKind.VANILLA_HUMANOID));
    }

    @Test
    void offsetCustomHelmetModelsUseEntityHeadPivotWithoutItemHardcoding() {
        assertFalse(ArmorHeadPivotPolicy.preserveNativeHeadPivot(
                ArmorEditorSettings.SlotKey.HEAD,
                ArmorModelTransformPolicy.RenderKind.CUSTOM_HUMANOID,
                new ArmorHeadPivot(0.0f, 24.0f, 0.0f),
                ArmorHeadPivot.IDENTITY
        ));
        assertFalse(ArmorHeadPivotPolicy.preserveNativeHeadPivot(
                ArmorEditorSettings.SlotKey.HEAD,
                ArmorModelTransformPolicy.RenderKind.EXTENDED_PIECES,
                ArmorHeadPivot.IDENTITY,
                new ArmorHeadPivot(0.0f, 2.0f, 0.0f)
        ));
    }

    @Test
    void customHelmetModelsWithMatchingPivotStillPreserveNativeHeadPivot() {
        assertTrue(ArmorHeadPivotPolicy.preserveNativeHeadPivot(
                ArmorEditorSettings.SlotKey.HEAD,
                ArmorModelTransformPolicy.RenderKind.CUSTOM_HUMANOID,
                ArmorHeadPivot.IDENTITY,
                ArmorHeadPivot.IDENTITY
        ));
    }

    @Test
    void customNonHeadModelsUseExistingPivotPolicy() {
        assertTrue(ArmorHeadPivotPolicy.preserveNativeHeadPivot(
                ArmorEditorSettings.SlotKey.CHEST,
                ArmorModelTransformPolicy.RenderKind.CUSTOM_HUMANOID,
                new ArmorHeadPivot(0.0f, 24.0f, 0.0f),
                ArmorHeadPivot.IDENTITY
        ));
    }

    @Test
    void vanillaHumanoidTransformsKeepExactEditorScale() {
        ArmorEditorTransform slot = new ArmorEditorTransform(0.0f, 0.140f, 0.093f, 1.600f, 1.230f, 2.260f);
        ArmorEditorTransform part = new ArmorEditorTransform(0.0f, 0.155f, -0.020f, 1.020f, 0.830f, 0.490f);

        ArmorEditorTransform tx = ArmorModelTransformPolicy.forRenderKind(ArmorEditorSettings.SlotKey.CHEST, ArmorModelTransformPolicy.RenderKind.VANILLA_HUMANOID, slot, part);

        assertTransformEquals(new ArmorEditorTransform(0.0f, 0.295f, 0.073f, 1.632f, 1.0209f, 1.1074f), tx);
    }

    @Test
    void customHumanoidTransformsKeepExactEditorScale() {
        ArmorEditorTransform slot = new ArmorEditorTransform(0.0f, 0.140f, 0.093f, 1.600f, 1.230f, 2.260f);
        ArmorEditorTransform part = new ArmorEditorTransform(0.0f, 0.155f, -0.020f, 1.020f, 0.830f, 0.490f);

        ArmorEditorTransform tx = ArmorModelTransformPolicy.forRenderKind(ArmorEditorSettings.SlotKey.CHEST, ArmorModelTransformPolicy.RenderKind.CUSTOM_HUMANOID, slot, part);

        assertTransformEquals(new ArmorEditorTransform(0.0f, 0.295f, 0.073f, 1.632f, 1.0209f, 1.1074f), tx);
    }

    @Test
    void extendedPieceTransformsKeepExactEditorScale() {
        ArmorEditorTransform slot = new ArmorEditorTransform(0.0f, 0.140f, 0.093f, 1.600f, 1.230f, 2.260f);
        ArmorEditorTransform part = new ArmorEditorTransform(0.0f, 0.155f, -0.020f, 1.020f, 0.830f, 0.490f);

        ArmorEditorTransform tx = ArmorModelTransformPolicy.forRenderKind(ArmorEditorSettings.SlotKey.CHEST, ArmorModelTransformPolicy.RenderKind.EXTENDED_PIECES, slot, part);

        assertTransformEquals(new ArmorEditorTransform(0.0f, 0.295f, 0.073f, 1.632f, 1.0209f, 1.1074f), tx);
    }

    @Test
    void wholeCustomModelTransformsUseSlotExactScale() {
        ArmorEditorTransform slot = new ArmorEditorTransform(0.0f, 0.140f, 0.093f, 1.600f, 1.230f, 2.260f);
        ArmorEditorTransform part = new ArmorEditorTransform(0.0f, 0.155f, -0.020f, 1.020f, 0.830f, 0.490f);

        ArmorEditorTransform tx = ArmorModelTransformPolicy.forWholeCustomModel(ArmorEditorSettings.SlotKey.CHEST, slot, part);

        assertTransformEquals(new ArmorEditorTransform(0.0f, 0.140f, 0.093f, 1.600f, 1.230f, 2.260f), tx);
    }

    @Test
    void customHeadTransformsKeepExactEditorScale() {
        ArmorEditorTransform slot = new ArmorEditorTransform(0.0f, 0.035f, 0.0f, 0.880f, 1.200f, 0.890f);
        ArmorEditorTransform part = ArmorEditorTransform.IDENTITY;

        ArmorEditorTransform tx = ArmorModelTransformPolicy.forRenderKind(ArmorEditorSettings.SlotKey.HEAD, ArmorModelTransformPolicy.RenderKind.CUSTOM_HUMANOID, slot, part);

        assertTransformEquals(new ArmorEditorTransform(0.0f, 0.035f, 0.0f, 0.880f, 1.200f, 0.890f), tx);
    }

    @Test
    void wholeCustomHeadModelUsesSlotExactScale() {
        ArmorEditorTransform slot = new ArmorEditorTransform(0.0f, 0.035f, 0.0f, 0.880f, 1.200f, 0.890f);

        ArmorEditorTransform tx = ArmorModelTransformPolicy.forWholeCustomModel(ArmorEditorSettings.SlotKey.HEAD, slot, ArmorEditorTransform.IDENTITY);

        assertTransformEquals(new ArmorEditorTransform(0.0f, 0.035f, 0.0f, 0.880f, 1.200f, 0.890f), tx);
    }

    private static void assertTransformEquals(ArmorEditorTransform expected, ArmorEditorTransform actual) {
        assertEquals(expected.x(), actual.x(), 0.0001f);
        assertEquals(expected.y(), actual.y(), 0.0001f);
        assertEquals(expected.z(), actual.z(), 0.0001f);
        assertEquals(expected.sx(), actual.sx(), 0.0001f);
        assertEquals(expected.sy(), actual.sy(), 0.0001f);
        assertEquals(expected.sz(), actual.sz(), 0.0001f);
    }
}
