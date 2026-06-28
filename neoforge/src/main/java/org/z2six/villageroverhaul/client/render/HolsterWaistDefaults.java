package org.z2six.villageroverhaul.client.render;

public final class HolsterWaistDefaults {
    public enum Profile {
        DEFAULT,
        BOW,
        CROSSBOW
    }

    private static final Transform DWARF_DEFAULT_BASELINE = new Transform(
            0.23000011f, 0.1000003f, 0.6199997f,
            -143.0f, 92.0f, 144.0f,
            1.0f,
            0.0f,
            "Z",
            30.0f,
            "Z"
    );
    private static volatile Transform DWARF_DEFAULT = DWARF_DEFAULT_BASELINE;

    private HolsterWaistDefaults() {}

    public static Transform dwarfTransform(Profile profile) {
        return DWARF_DEFAULT;
    }

    public static void resetDwarfTransform(Profile profile) {
        DWARF_DEFAULT = DWARF_DEFAULT_BASELINE;
    }

    public static boolean setDwarfTransform(Profile profile, String keyRaw, float value, boolean additive) {
        String key = keyRaw == null ? "" : keyRaw.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) return false;
        Transform t = DWARF_DEFAULT;
        DWARF_DEFAULT = switch (key) {
            case "tx" -> new Transform(additive ? t.tx + value : value, t.ty, t.tz, t.rxDeg, t.ryDeg, t.rzDeg, t.scale, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "ty" -> new Transform(t.tx, additive ? t.ty + value : value, t.tz, t.rxDeg, t.ryDeg, t.rzDeg, t.scale, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "tz" -> new Transform(t.tx, t.ty, additive ? t.tz + value : value, t.rxDeg, t.ryDeg, t.rzDeg, t.scale, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "rx", "rx_deg" -> new Transform(t.tx, t.ty, t.tz, additive ? t.rxDeg + value : value, t.ryDeg, t.rzDeg, t.scale, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "ry", "ry_deg" -> new Transform(t.tx, t.ty, t.tz, t.rxDeg, additive ? t.ryDeg + value : value, t.rzDeg, t.scale, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "rz", "rz_deg" -> new Transform(t.tx, t.ty, t.tz, t.rxDeg, t.ryDeg, additive ? t.rzDeg + value : value, t.scale, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "scale", "sx", "sy", "sz" -> new Transform(t.tx, t.ty, t.tz, t.rxDeg, t.ryDeg, t.rzDeg, additive ? t.scale + value : value, t.spinDeg, t.spinAxis, t.rollDeg, t.rollAxis);
            case "spin", "spin_deg" -> new Transform(t.tx, t.ty, t.tz, t.rxDeg, t.ryDeg, t.rzDeg, t.scale, additive ? t.spinDeg + value : value, t.spinAxis, t.rollDeg, t.rollAxis);
            case "roll", "roll_deg" -> new Transform(t.tx, t.ty, t.tz, t.rxDeg, t.ryDeg, t.rzDeg, t.scale, t.spinDeg, t.spinAxis, additive ? t.rollDeg + value : value, t.rollAxis);
            default -> t;
        };
        return DWARF_DEFAULT != t || key.equals("scale") || key.equals("sx") || key.equals("sy") || key.equals("sz");
    }

    public static String dwarfTweakString(Profile profile) {
        Transform t = dwarfTransform(profile);
        return "tx=" + t.tx
                + " ty=" + t.ty
                + " tz=" + t.tz
                + " rx=" + t.rxDeg
                + " ry=" + t.ryDeg
                + " rz=" + t.rzDeg
                + " scale=" + t.scale
                + " spin=" + t.spinDeg
                + " spinAxis=" + t.spinAxis
                + " roll=" + t.rollDeg
                + " rollAxis=" + t.rollAxis;
    }

    public record Transform(
            float tx,
            float ty,
            float tz,
            float rxDeg,
            float ryDeg,
            float rzDeg,
            float scale,
            float spinDeg,
            String spinAxis,
            float rollDeg,
            String rollAxis
    ) {}
}
