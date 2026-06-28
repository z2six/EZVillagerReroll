package org.z2six.villageroverhaul.client.render;

final class DwarfArmorGeometry {
    private DwarfArmorGeometry() {}

    static ArmorHeadPivot headArmorPivotFor(ArmorEditorProfile profile, ArmorEditorSettings.SlotKey slot) {
        if (profile != ArmorEditorProfile.DWARF || slot != ArmorEditorSettings.SlotKey.HEAD) {
            return ArmorHeadPivot.IDENTITY;
        }

        // Dwarf model root is offset +24 and its head is offset -22, so the visible head pivot is Y=2.
        return new ArmorHeadPivot(0.0f, 2.0f, 0.0f);
    }

    static ArmorPartPivot limbArmorPivotFor(ArmorEditorProfile profile,
                                            ArmorEditorSettings.SlotKey slot,
                                            ArmorEditorSettings.PartKey part) {
        if (profile != ArmorEditorProfile.DWARF || slot == null || part == null) {
            return ArmorPartPivot.IDENTITY;
        }

        return switch (slot) {
            case CHEST -> switch (part) {
                case RIGHT_ARM -> new ArmorPartPivot(-6.5f, 5.0f, 1.0f);
                case LEFT_ARM -> new ArmorPartPivot(6.5f, 5.0f, 1.0f);
                default -> ArmorPartPivot.IDENTITY;
            };
            case LEGS, FEET -> switch (part) {
                case RIGHT_LEG -> new ArmorPartPivot(-3.0f, 17.0f, 0.5f);
                case LEFT_LEG -> new ArmorPartPivot(3.0f, 17.0f, 0.5f);
                default -> ArmorPartPivot.IDENTITY;
            };
            default -> ArmorPartPivot.IDENTITY;
        };
    }
}
