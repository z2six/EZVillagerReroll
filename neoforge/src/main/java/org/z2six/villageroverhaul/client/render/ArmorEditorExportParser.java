package org.z2six.villageroverhaul.client.render;

import java.util.EnumMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArmorEditorExportParser {
    private static final Pattern HEADER = Pattern.compile(
            "\\[VillagerOverhaul] Armor editor export: ([A-Z_]+) / ([A-Z_]+)"
    );
    private static final Pattern SLOT = Pattern.compile(
            "slot ([A-Z_]+) = new ArmorEditorTransform\\(([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f\\);"
    );
    private static final Pattern PART = Pattern.compile(
            "part ([A-Z_]+) ([A-Z_]+) = new ArmorEditorTransform\\(([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f\\);"
    );

    private ArmorEditorExportParser() {
    }

    public static EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> parseAll(String export) {
        EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> out =
                new EnumMap<>(ArmorEditorProfile.class);
        if (export == null || export.isBlank()) return out;

        ArmorEditorProfile profile = null;
        ArmorEditorArmorKind armorKind = null;
        ArmorEditorSettings settings = null;

        for (String raw : export.split("\\R")) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;

            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                profile = ArmorEditorProfile.valueOf(header.group(1).toUpperCase(Locale.ROOT));
                armorKind = ArmorEditorArmorKind.valueOf(header.group(2).toUpperCase(Locale.ROOT));
                settings = defaultsFor(profile, armorKind).copy();
                out.computeIfAbsent(profile, ignored -> new EnumMap<>(ArmorEditorArmorKind.class))
                        .put(armorKind, settings);
                continue;
            }

            if (settings == null) continue;

            Matcher slot = SLOT.matcher(line);
            if (slot.matches()) {
                settings.setSlotTransform(
                        ArmorEditorSettings.SlotKey.valueOf(slot.group(1).toUpperCase(Locale.ROOT)),
                        transform(slot, 2)
                );
                continue;
            }

            Matcher part = PART.matcher(line);
            if (part.matches()) {
                settings.setPartTransform(
                        ArmorEditorSettings.SlotKey.valueOf(part.group(1).toUpperCase(Locale.ROOT)),
                        ArmorEditorSettings.PartKey.valueOf(part.group(2).toUpperCase(Locale.ROOT)),
                        transform(part, 3)
                );
            }
        }

        return out;
    }

    private static ArmorEditorSettings defaultsFor(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind) {
        ArmorEditorProfile p = profile == null ? ArmorEditorProfile.VILLAGER : profile;
        ArmorEditorArmorKind k = armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind;
        if (p == ArmorEditorProfile.DWARF) {
            return k == ArmorEditorArmorKind.MODDED
                    ? ArmorEditorSettings.dwarfModdedDefaults()
                    : ArmorEditorSettings.dwarfDefaults();
        }
        return k == ArmorEditorArmorKind.MODDED
                ? ArmorEditorSettings.moddedDefaults()
                : ArmorEditorSettings.defaults();
    }

    private static ArmorEditorTransform transform(Matcher matcher, int offset) {
        return new ArmorEditorTransform(
                Float.parseFloat(matcher.group(offset)),
                Float.parseFloat(matcher.group(offset + 1)),
                Float.parseFloat(matcher.group(offset + 2)),
                Float.parseFloat(matcher.group(offset + 3)),
                Float.parseFloat(matcher.group(offset + 4)),
                Float.parseFloat(matcher.group(offset + 5))
        );
    }
}
