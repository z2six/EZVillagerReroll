package org.z2six.villageroverhaul.client.render;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class ArmorEditorSettings {

    public enum SlotKey {
        HEAD,
        CHEST,
        LEGS,
        FEET
    }

    public enum PartKey {
        HEAD,
        HAT,
        BODY,
        RIGHT_ARM,
        LEFT_ARM,
        RIGHT_LEG,
        LEFT_LEG
    }

    private final EnumMap<SlotKey, ArmorEditorTransform> slotTransforms = new EnumMap<>(SlotKey.class);
    private final EnumMap<SlotKey, EnumMap<PartKey, ArmorEditorTransform>> partTransforms = new EnumMap<>(SlotKey.class);

    private ArmorEditorSettings() {
    }

    public static ArmorEditorSettings defaults() {
        ArmorEditorSettings s = new ArmorEditorSettings();

        s.slotTransforms.put(SlotKey.HEAD, new ArmorEditorTransform(0.000f, 0.025f, 0.005f, 1.000f, 1.250f, 1.000f));
        s.slotTransforms.put(SlotKey.CHEST, new ArmorEditorTransform(0.000f, 0.000f, 0.012f, 1.000f, 1.060f, 1.270f));
        s.slotTransforms.put(SlotKey.LEGS, new ArmorEditorTransform(0.000f, -0.325f, 0.000f, 1.040f, 1.060f, 1.180f));
        s.slotTransforms.put(SlotKey.FEET, new ArmorEditorTransform(0.000f, -0.095f, 0.026f, 1.030f, 1.060f, 1.070f));

        for (SlotKey slot : SlotKey.values()) {
            EnumMap<PartKey, ArmorEditorTransform> parts = new EnumMap<>(PartKey.class);
            for (PartKey part : PartKey.values()) {
                parts.put(part, ArmorEditorTransform.IDENTITY);
            }
            s.partTransforms.put(slot, parts);
        }

        s.setPartTransform(SlotKey.CHEST, PartKey.BODY, new ArmorEditorTransform(0.0f, 0.000f, 0.000f, 1.070f, 1.070f, 1.070f));
        s.setPartTransform(SlotKey.CHEST, PartKey.RIGHT_ARM, new ArmorEditorTransform(0.070f, -0.180f, -0.070f, 1.020f, 0.970f, 0.800f));
        s.setPartTransform(SlotKey.CHEST, PartKey.LEFT_ARM, new ArmorEditorTransform(-0.070f, -0.180f, -0.070f, 1.020f, 0.970f, 0.800f));

        s.setPartTransform(SlotKey.LEGS, PartKey.BODY, new ArmorEditorTransform(0.0f, 0.000f, 0.000f, 1.040f, 1.040f, 1.040f));
        s.setPartTransform(SlotKey.LEGS, PartKey.RIGHT_LEG, new ArmorEditorTransform(0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f));
        s.setPartTransform(SlotKey.LEGS, PartKey.LEFT_LEG, new ArmorEditorTransform(-0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f));

        s.setPartTransform(SlotKey.FEET, PartKey.RIGHT_LEG, new ArmorEditorTransform(0.0f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f));
        s.setPartTransform(SlotKey.FEET, PartKey.LEFT_LEG, new ArmorEditorTransform(0.0f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f));

        return s;
    }

    public static ArmorEditorSettings dwarfDefaults() {
        ArmorEditorSettings s = defaults();
        s.setSlotTransform(SlotKey.HEAD, new ArmorEditorTransform(0.000f, 0.305f, -0.169f, 1.190f, 1.250f, 1.300f));
        s.setSlotTransform(SlotKey.CHEST, new ArmorEditorTransform(0.000f, 0.165f, 0.093f, 1.450f, 1.110f, 1.910f));
        s.setSlotTransform(SlotKey.LEGS, new ArmorEditorTransform(0.000f, -0.150f, 0.005f, 1.300f, 0.790f, 1.250f));
        s.setSlotTransform(SlotKey.FEET, new ArmorEditorTransform(0.000f, -0.145f, 0.001f, 1.220f, 0.760f, 1.170f));
        s.setPartTransform(SlotKey.CHEST, PartKey.RIGHT_ARM, new ArmorEditorTransform(-0.075f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f));
        s.setPartTransform(SlotKey.CHEST, PartKey.LEFT_ARM, new ArmorEditorTransform(0.075f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f));
        resetIndividualLegParts(s);
        return s;
    }

    public static ArmorEditorSettings moddedDefaults() {
        ArmorEditorSettings s = defaults();
        s.setSlotTransform(SlotKey.CHEST, new ArmorEditorTransform(0.000f, 0.000f, 0.002f, 1.000f, 1.060f, 1.270f));
        s.setSlotTransform(SlotKey.FEET, new ArmorEditorTransform(0.000f, -0.075f, 0.006f, 0.960f, 1.060f, 1.070f));
        return s;
    }

    public static ArmorEditorSettings dwarfModdedDefaults() {
        ArmorEditorSettings s = defaults();
        s.setSlotTransform(SlotKey.HEAD, new ArmorEditorTransform(0.000f, 0.280f, -0.169f, 1.200f, 1.250f, 1.300f));
        s.setSlotTransform(SlotKey.CHEST, new ArmorEditorTransform(0.000f, 0.165f, 0.093f, 1.450f, 1.110f, 2.310f));
        s.setSlotTransform(SlotKey.LEGS, new ArmorEditorTransform(0.000f, 0.045f, 0.005f, 1.700f, 0.540f, 1.350f));
        s.setSlotTransform(SlotKey.FEET, new ArmorEditorTransform(0.000f, -0.345f, 0.001f, 1.390f, 1.060f, 1.320f));
        ArmorEditorTransform chestArm = new ArmorEditorTransform(0.000f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f);
        s.setPartTransform(SlotKey.CHEST, PartKey.RIGHT_ARM, chestArm);
        s.setPartTransform(SlotKey.CHEST, PartKey.LEFT_ARM, chestArm);
        return s;
    }

    private static void resetIndividualLegParts(ArmorEditorSettings settings) {
        settings.setPartTransform(SlotKey.LEGS, PartKey.RIGHT_LEG, new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f));
        settings.setPartTransform(SlotKey.LEGS, PartKey.LEFT_LEG, new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f));
    }

    public ArmorEditorSettings copy() {
        ArmorEditorSettings out = new ArmorEditorSettings();
        out.slotTransforms.putAll(this.slotTransforms);
        for (Map.Entry<SlotKey, EnumMap<PartKey, ArmorEditorTransform>> e : this.partTransforms.entrySet()) {
            EnumMap<PartKey, ArmorEditorTransform> parts = new EnumMap<>(PartKey.class);
            parts.putAll(e.getValue());
            out.partTransforms.put(e.getKey(), parts);
        }
        return out;
    }

    public ArmorEditorTransform slotTransform(SlotKey slot) {
        if (slot == null) return ArmorEditorTransform.IDENTITY;
        ArmorEditorTransform tx = slotTransforms.get(slot);
        return tx == null ? ArmorEditorTransform.IDENTITY : tx;
    }

    public ArmorEditorTransform partTransform(SlotKey slot, PartKey part) {
        if (slot == null || part == null) return ArmorEditorTransform.IDENTITY;
        EnumMap<PartKey, ArmorEditorTransform> parts = partTransforms.get(slot);
        if (parts == null) return ArmorEditorTransform.IDENTITY;
        ArmorEditorTransform tx = parts.get(part);
        return tx == null ? ArmorEditorTransform.IDENTITY : tx;
    }

    public void setSlotTransform(SlotKey slot, ArmorEditorTransform transform) {
        if (slot == null || transform == null) return;
        slotTransforms.put(slot, transform);
    }

    public void setPartTransform(SlotKey slot, PartKey part, ArmorEditorTransform transform) {
        if (slot == null || part == null || transform == null) return;
        EnumMap<PartKey, ArmorEditorTransform> parts = partTransforms.computeIfAbsent(slot, ignored -> new EnumMap<>(PartKey.class));
        parts.put(part, transform);
    }

    public void resetSlot(SlotKey slot) {
        if (slot == null) return;
        setSlotTransform(slot, defaults().slotTransform(slot));
    }

    public void resetPart(SlotKey slot, PartKey part) {
        if (slot == null || part == null) return;
        setPartTransform(slot, part, defaults().partTransform(slot, part));
    }

    public String exportHardcodeLines() {
        return exportHardcodeLines(null);
    }

    public String exportHardcodeLines(String header) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(header == null || header.isBlank() ? "[VillagerOverhaul] Armor editor export" : header).append('\n');
        for (SlotKey slot : SlotKey.values()) {
            sb.append("slot ")
                    .append(slot.name())
                    .append(" = ")
                    .append(toHardcode(slotTransform(slot)))
                    .append(";\n");
        }
        for (SlotKey slot : SlotKey.values()) {
            for (PartKey part : PartKey.values()) {
                sb.append("part ")
                        .append(slot.name())
                        .append(' ')
                        .append(part.name())
                        .append(" = ")
                        .append(toHardcode(partTransform(slot, part)))
                        .append(";\n");
            }
        }
        return sb.toString();
    }

    public String toPropertiesText() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("# VillagerOverhaul armor editor settings\n");
        for (SlotKey slot : SlotKey.values()) {
            sb.append("slot.").append(slot.name()).append('=').append(slotTransform(slot).toCsv()).append('\n');
        }
        for (SlotKey slot : SlotKey.values()) {
            for (PartKey part : PartKey.values()) {
                sb.append("part.")
                        .append(slot.name())
                        .append('.')
                        .append(part.name())
                        .append('=')
                        .append(partTransform(slot, part).toCsv())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    public static ArmorEditorSettings fromPropertiesText(String text) {
        ArmorEditorSettings settings = defaults();
        if (text == null || text.isBlank()) return settings;
        String[] lines = text.split("\\R");
        for (String raw : lines) {
            try {
                if (raw == null) continue;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                if (key.startsWith("slot.")) {
                    SlotKey slot = SlotKey.valueOf(key.substring("slot.".length()).trim().toUpperCase(Locale.ROOT));
                    settings.setSlotTransform(slot, ArmorEditorTransform.fromCsv(value, settings.slotTransform(slot)));
                    continue;
                }

                if (key.startsWith("part.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length != 3) continue;
                    SlotKey slot = SlotKey.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
                    PartKey part = PartKey.valueOf(parts[2].trim().toUpperCase(Locale.ROOT));
                    settings.setPartTransform(slot, part, ArmorEditorTransform.fromCsv(value, settings.partTransform(slot, part)));
                }
            } catch (Throwable ignored) {
            }
        }
        return settings;
    }

    private static String toHardcode(ArmorEditorTransform tx) {
        ArmorEditorTransform t = tx == null ? ArmorEditorTransform.IDENTITY : tx;
        return String.format(Locale.ROOT, "new ArmorEditorTransform(%.3ff, %.3ff, %.3ff, %.3ff, %.3ff, %.3ff)",
                t.x(), t.y(), t.z(), t.sx(), t.sy(), t.sz());
    }
}
