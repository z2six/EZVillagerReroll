package org.z2six.villageroverhaul.client.render;

import java.util.List;

public record ArmorEditorTransformTarget(Kind kind) {
    public enum Kind {
        SLOT,
        ARMS,
        RIGHT_ARM,
        LEFT_ARM,
        LEGS,
        FEET,
        RIGHT_LEG,
        LEFT_LEG
    }

    private static final ArmorEditorTransformTarget SLOT = new ArmorEditorTransformTarget(Kind.SLOT);
    private static final ArmorEditorTransformTarget ARMS = new ArmorEditorTransformTarget(Kind.ARMS);
    private static final ArmorEditorTransformTarget RIGHT_ARM = new ArmorEditorTransformTarget(Kind.RIGHT_ARM);
    private static final ArmorEditorTransformTarget LEFT_ARM = new ArmorEditorTransformTarget(Kind.LEFT_ARM);
    private static final ArmorEditorTransformTarget LEGS = new ArmorEditorTransformTarget(Kind.LEGS);
    private static final ArmorEditorTransformTarget FEET = new ArmorEditorTransformTarget(Kind.FEET);
    private static final ArmorEditorTransformTarget RIGHT_LEG = new ArmorEditorTransformTarget(Kind.RIGHT_LEG);
    private static final ArmorEditorTransformTarget LEFT_LEG = new ArmorEditorTransformTarget(Kind.LEFT_LEG);

    public static ArmorEditorTransformTarget slot() {
        return SLOT;
    }

    public static ArmorEditorTransformTarget arms() {
        return ARMS;
    }

    public static ArmorEditorTransformTarget rightArm() {
        return RIGHT_ARM;
    }

    public static ArmorEditorTransformTarget leftArm() {
        return LEFT_ARM;
    }

    public static ArmorEditorTransformTarget legs() {
        return LEGS;
    }

    public static ArmorEditorTransformTarget feet() {
        return FEET;
    }

    public static ArmorEditorTransformTarget rightLeg() {
        return RIGHT_LEG;
    }

    public static ArmorEditorTransformTarget leftLeg() {
        return LEFT_LEG;
    }

    public boolean slotTarget() {
        return kind == Kind.SLOT;
    }

    public boolean armsTarget() {
        return kind == Kind.ARMS;
    }

    public boolean legsTarget() {
        return kind == Kind.LEGS || kind == Kind.FEET;
    }

    public ArmorEditorSettings.PartKey partTarget() {
        return switch (kind) {
            case RIGHT_ARM -> ArmorEditorSettings.PartKey.RIGHT_ARM;
            case LEFT_ARM -> ArmorEditorSettings.PartKey.LEFT_ARM;
            case RIGHT_LEG -> ArmorEditorSettings.PartKey.RIGHT_LEG;
            case LEFT_LEG -> ArmorEditorSettings.PartKey.LEFT_LEG;
            default -> null;
        };
    }

    public String label() {
        return switch (kind) {
            case SLOT -> "Slot";
            case ARMS -> "Arms";
            case RIGHT_ARM -> "R Arm";
            case LEFT_ARM -> "L Arm";
            case LEGS -> "Legs";
            case FEET -> "Feet";
            case RIGHT_LEG -> "R Leg";
            case LEFT_LEG -> "L Leg";
        };
    }

    public static List<ArmorEditorTransformTarget> targetsForSlot(ArmorEditorSettings.SlotKey slot) {
        if (slot == null) return List.of(SLOT);
        return switch (slot) {
            case CHEST -> List.of(SLOT, ARMS, RIGHT_ARM, LEFT_ARM);
            case LEGS -> List.of(SLOT, LEGS, RIGHT_LEG, LEFT_LEG);
            case FEET -> List.of(SLOT, FEET, RIGHT_LEG, LEFT_LEG);
            case HEAD -> List.of(SLOT);
        };
    }

    public static ArmorEditorTransformTarget normalizeForSlot(ArmorEditorSettings.SlotKey slot,
                                                              ArmorEditorTransformTarget target) {
        if (target == null || target.slotTarget()) return SLOT;
        for (ArmorEditorTransformTarget allowed : targetsForSlot(slot)) {
            if (allowed.equals(target)) return target;
        }
        return SLOT;
    }
}
