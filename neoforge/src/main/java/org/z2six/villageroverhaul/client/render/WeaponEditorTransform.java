package org.z2six.villageroverhaul.client.render;

import java.util.Locale;

public record WeaponEditorTransform(
        float tx,
        float ty,
        float tz,
        float rxDeg,
        float ryDeg,
        float rzDeg,
        float sx,
        float sy,
        float sz
) {
    public static final WeaponEditorTransform IDENTITY =
            new WeaponEditorTransform(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);

    public WeaponEditorTransform with(String keyRaw, float value, boolean additive) {
        String key = keyRaw == null ? "" : keyRaw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "tx", "x" -> new WeaponEditorTransform(additive ? tx + value : value, ty, tz, rxDeg, ryDeg, rzDeg, sx, sy, sz);
            case "ty", "y" -> new WeaponEditorTransform(tx, additive ? ty + value : value, tz, rxDeg, ryDeg, rzDeg, sx, sy, sz);
            case "tz", "z" -> new WeaponEditorTransform(tx, ty, additive ? tz + value : value, rxDeg, ryDeg, rzDeg, sx, sy, sz);
            case "rx" -> new WeaponEditorTransform(tx, ty, tz, additive ? rxDeg + value : value, ryDeg, rzDeg, sx, sy, sz);
            case "ry" -> new WeaponEditorTransform(tx, ty, tz, rxDeg, additive ? ryDeg + value : value, rzDeg, sx, sy, sz);
            case "rz" -> new WeaponEditorTransform(tx, ty, tz, rxDeg, ryDeg, additive ? rzDeg + value : value, sx, sy, sz);
            case "sx" -> new WeaponEditorTransform(tx, ty, tz, rxDeg, ryDeg, rzDeg, additive ? sx + value : value, sy, sz);
            case "sy" -> new WeaponEditorTransform(tx, ty, tz, rxDeg, ryDeg, rzDeg, sx, additive ? sy + value : value, sz);
            case "sz" -> new WeaponEditorTransform(tx, ty, tz, rxDeg, ryDeg, rzDeg, sx, sy, additive ? sz + value : value);
            default -> this;
        };
    }

    public String toHardcode() {
        return String.format(Locale.ROOT,
                "new WeaponEditorTransform(%.3ff, %.3ff, %.3ff, %.3ff, %.3ff, %.3ff, %.3ff, %.3ff, %.3ff)",
                tx, ty, tz, rxDeg, ryDeg, rzDeg, sx, sy, sz);
    }

    public String compact() {
        return String.format(Locale.ROOT,
                "tx=%.3f ty=%.3f tz=%.3f rx=%.1f ry=%.1f rz=%.1f sx=%.3f sy=%.3f sz=%.3f",
                tx, ty, tz, rxDeg, ryDeg, rzDeg, sx, sy, sz);
    }
}
