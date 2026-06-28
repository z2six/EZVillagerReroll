package org.z2six.villageroverhaul.client.render;

final class ArmorHeadPivotPolicy {
    private static final float PIVOT_EPSILON = 0.001f;

    private ArmorHeadPivotPolicy() {}

    static boolean preserveNativeHeadPivot(ArmorEditorSettings.SlotKey slot,
                                           ArmorModelTransformPolicy.RenderKind renderKind,
                                           ArmorHeadPivot nativePivot,
                                           ArmorHeadPivot entityPivot) {
        if (!ArmorModelTransformPolicy.preserveNativeHeadPivot(renderKind)) {
            return false;
        }
        if (slot != ArmorEditorSettings.SlotKey.HEAD || renderKind == ArmorModelTransformPolicy.RenderKind.CUSTOM_MODEL) {
            return true;
        }
        if (nativePivot == null || entityPivot == null) {
            return true;
        }
        return samePivot(nativePivot, entityPivot);
    }

    private static boolean samePivot(ArmorHeadPivot a, ArmorHeadPivot b) {
        return Math.abs(a.x() - b.x()) <= PIVOT_EPSILON
                && Math.abs(a.y() - b.y()) <= PIVOT_EPSILON
                && Math.abs(a.z() - b.z()) <= PIVOT_EPSILON;
    }
}
