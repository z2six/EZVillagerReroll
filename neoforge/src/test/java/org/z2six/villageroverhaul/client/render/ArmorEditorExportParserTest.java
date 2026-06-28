package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Locale;

final class ArmorEditorExportParserTest {

    @Test
    void parsesEveryProfileArmorKindSlotAndPartFromEditorExport() {
        EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> source =
                new EnumMap<>(ArmorEditorProfile.class);
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            EnumMap<ArmorEditorArmorKind, ArmorEditorSettings> byKind = new EnumMap<>(ArmorEditorArmorKind.class);
            for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                byKind.put(armorKind, uniqueSettings(profile, armorKind));
            }
            source.put(profile, byKind);
        }

        String export = ArmorEditorRuntimeSettings.exportAllHardcodeLines((profile, armorKind) ->
                source.get(profile).get(armorKind));

        EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> parsed =
                ArmorEditorExportParser.parseAll(export);

        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            EnumMap<ArmorEditorArmorKind, ArmorEditorSettings> parsedByKind = parsed.get(profile);
            assertNotNull(parsedByKind, profile.name());
            for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                ArmorEditorSettings expected = source.get(profile).get(armorKind);
                ArmorEditorSettings actual = parsedByKind.get(armorKind);
                assertNotNull(actual, profile.name() + " / " + armorKind.name());
                assertSettingsEqual(expected, actual);
            }
        }
    }

    private static ArmorEditorSettings uniqueSettings(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind) {
        ArmorEditorSettings settings = ArmorEditorSettings.defaults();
        int base = profile.ordinal() * 1000 + armorKind.ordinal() * 100;

        for (ArmorEditorSettings.SlotKey slot : ArmorEditorSettings.SlotKey.values()) {
            settings.setSlotTransform(slot, transform(base + slot.ordinal() * 10));
        }

        for (ArmorEditorSettings.SlotKey slot : ArmorEditorSettings.SlotKey.values()) {
            for (ArmorEditorSettings.PartKey part : ArmorEditorSettings.PartKey.values()) {
                settings.setPartTransform(slot, part, transform(base + 40 + slot.ordinal() * 70 + part.ordinal() * 10));
            }
        }

        return settings;
    }

    private static ArmorEditorTransform transform(int seed) {
        float s = seed / 1000.0f;
        return new ArmorEditorTransform(
                exportedFloat(s + 0.001f),
                exportedFloat(s + 0.002f),
                exportedFloat(s + 0.003f),
                exportedFloat(s + 1.004f),
                exportedFloat(s + 1.005f),
                exportedFloat(s + 1.006f)
        );
    }

    private static float exportedFloat(float value) {
        return Float.parseFloat(String.format(Locale.ROOT, "%.3f", value));
    }

    private static void assertSettingsEqual(ArmorEditorSettings expected, ArmorEditorSettings actual) {
        for (ArmorEditorSettings.SlotKey slot : ArmorEditorSettings.SlotKey.values()) {
            assertEquals(expected.slotTransform(slot), actual.slotTransform(slot), "slot " + slot.name());
        }
        for (ArmorEditorSettings.SlotKey slot : ArmorEditorSettings.SlotKey.values()) {
            for (ArmorEditorSettings.PartKey part : ArmorEditorSettings.PartKey.values()) {
                assertEquals(expected.partTransform(slot, part), actual.partTransform(slot, part),
                        "part " + slot.name() + " " + part.name());
            }
        }
    }
}
