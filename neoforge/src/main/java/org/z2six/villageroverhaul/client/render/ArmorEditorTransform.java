package org.z2six.villageroverhaul.client.render;

import java.util.Locale;

public record ArmorEditorTransform(float x, float y, float z, float sx, float sy, float sz) {
    public static final ArmorEditorTransform IDENTITY = new ArmorEditorTransform(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f);

    public boolean isIdentity() {
        return x == 0.0f && y == 0.0f && z == 0.0f && sx == 1.0f && sy == 1.0f && sz == 1.0f;
    }

    public ArmorEditorTransform withParam(String param, float value) {
        String p = param == null ? "" : param.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "x", "tx" -> new ArmorEditorTransform(value, y, z, sx, sy, sz);
            case "y", "ty" -> new ArmorEditorTransform(x, value, z, sx, sy, sz);
            case "z", "tz" -> new ArmorEditorTransform(x, y, value, sx, sy, sz);
            case "sx" -> new ArmorEditorTransform(x, y, z, value, sy, sz);
            case "sy" -> new ArmorEditorTransform(x, y, z, sx, value, sz);
            case "sz" -> new ArmorEditorTransform(x, y, z, sx, sy, value);
            default -> this;
        };
    }

    public ArmorEditorTransform adjust(String param, float delta) {
        String p = param == null ? "" : param.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "x", "tx" -> withParam("x", x + delta);
            case "y", "ty" -> withParam("y", y + delta);
            case "z", "tz" -> withParam("z", z + delta);
            case "sx" -> withParam("sx", Math.max(0.01f, sx + delta));
            case "sy" -> withParam("sy", Math.max(0.01f, sy + delta));
            case "sz" -> withParam("sz", Math.max(0.01f, sz + delta));
            default -> this;
        };
    }

    public String toCsv() {
        return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f", x, y, z, sx, sy, sz);
    }

    public String toHardcode() {
        return String.format(Locale.ROOT, "new ArmorEditorTransform(%.3ff, %.3ff, %.3ff, %.3ff, %.3ff, %.3ff)", x, y, z, sx, sy, sz);
    }

    public String toDisplay() {
        return String.format(Locale.ROOT, "x %.3f  y %.3f  z %.3f  sx %.3f  sy %.3f  sz %.3f", x, y, z, sx, sy, sz);
    }

    public static ArmorEditorTransform fromCsv(String text, ArmorEditorTransform fallback) {
        ArmorEditorTransform fb = fallback == null ? IDENTITY : fallback;
        try {
            if (text == null || text.isBlank()) return fb;
            String[] parts = text.trim().split("\\s*,\\s*");
            if (parts.length != 6) return fb;
            return new ArmorEditorTransform(
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5])
            );
        } catch (Throwable ignored) {
            return fb;
        }
    }
}
