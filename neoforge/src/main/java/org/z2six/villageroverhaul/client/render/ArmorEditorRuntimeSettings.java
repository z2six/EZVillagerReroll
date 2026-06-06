package org.z2six.villageroverhaul.client.render;

import net.minecraft.world.entity.EquipmentSlot;

public final class ArmorEditorRuntimeSettings {
    private static ArmorEditorSettings transientSettings;

    private ArmorEditorRuntimeSettings() {}

    public static synchronized ArmorEditorSettings snapshot() {
        return currentSettings().copy();
    }

    public static synchronized void applyTransient(ArmorEditorSettings next) {
        transientSettings = next == null ? null : next.copy();
    }

    public static synchronized void save(ArmorEditorSettings next) {
        applyTransient(next);
    }

    public static synchronized String exportHardcodeLines() {
        return currentSettings().exportHardcodeLines();
    }

    public static ArmorEditorTransform slotTransform(EquipmentSlot slot) {
        ArmorEditorSettings.SlotKey key = slotKey(slot);
        if (key == null) return ArmorEditorTransform.IDENTITY;
        synchronized (ArmorEditorRuntimeSettings.class) {
            return currentSettings().slotTransform(key);
        }
    }

    public static ArmorEditorTransform partTransform(EquipmentSlot slot, ArmorEditorSettings.PartKey part) {
        ArmorEditorSettings.SlotKey key = slotKey(slot);
        if (key == null || part == null) return ArmorEditorTransform.IDENTITY;
        synchronized (ArmorEditorRuntimeSettings.class) {
            return currentSettings().partTransform(key, part);
        }
    }

    public static ArmorEditorSettings.SlotKey slotKey(EquipmentSlot slot) {
        if (slot == null) return null;
        return switch (slot) {
            case HEAD -> ArmorEditorSettings.SlotKey.HEAD;
            case CHEST -> ArmorEditorSettings.SlotKey.CHEST;
            case LEGS -> ArmorEditorSettings.SlotKey.LEGS;
            case FEET -> ArmorEditorSettings.SlotKey.FEET;
            default -> null;
        };
    }

    private static ArmorEditorSettings currentSettings() {
        return transientSettings == null ? ArmorEditorSettings.defaults() : transientSettings;
    }
}
