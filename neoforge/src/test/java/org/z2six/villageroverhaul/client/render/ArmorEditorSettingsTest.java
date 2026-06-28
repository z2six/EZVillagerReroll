package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

final class ArmorEditorSettingsTest {

    @Test
    void exportsSortedHardcodeLinesForChangedValues() {
        ArmorEditorSettings settings = ArmorEditorSettings.defaults();
        settings.setSlotTransform(
                ArmorEditorSettings.SlotKey.HEAD,
                new ArmorEditorTransform(0.25f, -0.5f, 0.75f, 1.25f, 1.5f, 1.75f)
        );
        settings.setPartTransform(
                ArmorEditorSettings.SlotKey.CHEST,
                ArmorEditorSettings.PartKey.BODY,
                new ArmorEditorTransform(0.1f, 0.2f, 0.3f, 1.1f, 1.2f, 1.3f)
        );

        String export = settings.exportHardcodeLines();

        assertTrue(export.contains("slot HEAD = new ArmorEditorTransform(0.250f, -0.500f, 0.750f, 1.250f, 1.500f, 1.750f);"));
        assertTrue(export.contains("part CHEST BODY = new ArmorEditorTransform(0.100f, 0.200f, 0.300f, 1.100f, 1.200f, 1.300f);"));
        assertTrue(export.indexOf("slot HEAD") < export.indexOf("part CHEST BODY"));
    }

    @Test
    void serializesAndParsesTransformsWithoutLosingValues() {
        ArmorEditorSettings settings = ArmorEditorSettings.defaults();
        ArmorEditorTransform transform = new ArmorEditorTransform(-0.125f, 0.5f, 0.625f, 0.95f, 1.05f, 1.15f);
        settings.setPartTransform(ArmorEditorSettings.SlotKey.FEET, ArmorEditorSettings.PartKey.RIGHT_LEG, transform);

        ArmorEditorSettings parsed = ArmorEditorSettings.fromPropertiesText(settings.toPropertiesText());

        assertEquals(transform, parsed.partTransform(ArmorEditorSettings.SlotKey.FEET, ArmorEditorSettings.PartKey.RIGHT_LEG));
    }

    @Test
    void resettingSlotTransformKeepsPartTransformOverrides() {
        ArmorEditorSettings settings = ArmorEditorSettings.defaults();
        ArmorEditorTransform partOverride = new ArmorEditorTransform(0.1f, 0.2f, 0.3f, 1.1f, 1.2f, 1.3f);
        settings.setSlotTransform(ArmorEditorSettings.SlotKey.LEGS,
                new ArmorEditorTransform(0.4f, 0.5f, 0.6f, 1.4f, 1.5f, 1.6f));
        settings.setPartTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG, partOverride);

        settings.resetSlot(ArmorEditorSettings.SlotKey.LEGS);

        assertEquals(ArmorEditorSettings.defaults().slotTransform(ArmorEditorSettings.SlotKey.LEGS),
                settings.slotTransform(ArmorEditorSettings.SlotKey.LEGS));
        assertEquals(partOverride, settings.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
    }

    @Test
    void runtimeSaveDoesNotCreatePersistentConfigFile() throws Exception {
        Path path = Path.of("config").resolve("villageroverhaul_armor_editor.properties");
        Files.deleteIfExists(path);
        try {
            ArmorEditorRuntimeSettings.save(ArmorEditorSettings.defaults());

            assertFalse(Files.exists(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void defaultsUseCurrentEditorBaseline() {
        ArmorEditorSettings settings = ArmorEditorSettings.defaults();

        assertEquals(new ArmorEditorTransform(0.000f, 0.025f, 0.005f, 1.000f, 1.250f, 1.000f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.012f, 1.000f, 1.060f, 1.270f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(0.000f, -0.325f, 0.000f, 1.040f, 1.060f, 1.180f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.LEGS));
        assertEquals(new ArmorEditorTransform(0.000f, -0.095f, 0.026f, 1.030f, 1.060f, 1.070f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.FEET));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.000f, 1.000f, 1.000f),
                settings.partTransform(ArmorEditorSettings.SlotKey.HEAD, ArmorEditorSettings.PartKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.070f, 1.070f, 1.070f),
                settings.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.BODY));
        assertEquals(new ArmorEditorTransform(0.070f, -0.180f, -0.070f, 1.020f, 0.970f, 0.800f),
                settings.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.RIGHT_ARM));
        assertEquals(new ArmorEditorTransform(-0.070f, -0.180f, -0.070f, 1.020f, 0.970f, 0.800f),
                settings.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.LEFT_ARM));
        assertEquals(new ArmorEditorTransform(0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                settings.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.RIGHT_LEG));
        assertEquals(new ArmorEditorTransform(-0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                settings.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
    }

    @Test
    void runtimeDwarfDefaultsUseFittedArmorWithoutChangingVillagerDefaults() {
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.VILLAGER, null);
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.DWARF, null);

        ArmorEditorSettings villager = ArmorEditorRuntimeSettings.snapshot(ArmorEditorProfile.VILLAGER);
        ArmorEditorSettings dwarf = ArmorEditorRuntimeSettings.snapshot(ArmorEditorProfile.DWARF);

        assertEquals(new ArmorEditorTransform(0.000f, 0.025f, 0.005f, 1.000f, 1.250f, 1.000f),
                villager.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, -0.325f, 0.000f, 1.040f, 1.060f, 1.180f),
                villager.slotTransform(ArmorEditorSettings.SlotKey.LEGS));
        assertEquals(new ArmorEditorTransform(0.000f, -0.095f, 0.026f, 1.030f, 1.060f, 1.070f),
                villager.slotTransform(ArmorEditorSettings.SlotKey.FEET));
        assertEquals(new ArmorEditorTransform(0.000f, 0.305f, -0.169f, 1.190f, 1.250f, 1.300f),
                dwarf.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.165f, 0.093f, 1.450f, 1.110f, 1.910f),
                dwarf.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(-0.075f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f),
                dwarf.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.RIGHT_ARM));
        assertEquals(new ArmorEditorTransform(0.075f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f),
                dwarf.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.LEFT_ARM));
        assertEquals(new ArmorEditorTransform(0.000f, -0.150f, 0.005f, 1.300f, 0.790f, 1.250f),
                dwarf.slotTransform(ArmorEditorSettings.SlotKey.LEGS));
        assertEquals(new ArmorEditorTransform(0.000f, -0.145f, 0.001f, 1.220f, 0.760f, 1.170f),
                dwarf.slotTransform(ArmorEditorSettings.SlotKey.FEET));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                dwarf.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.RIGHT_LEG));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                dwarf.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
        assertEquals(villager.partTransform(ArmorEditorSettings.SlotKey.HEAD, ArmorEditorSettings.PartKey.HEAD),
                dwarf.partTransform(ArmorEditorSettings.SlotKey.HEAD, ArmorEditorSettings.PartKey.HEAD));
        assertEquals(villager.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.BODY),
                dwarf.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.BODY));
    }

    @Test
    void dwarfModdedDefaultsAreSeparateFromDwarfVanillaDefaults() {
        ArmorEditorSettings vanilla = ArmorEditorSettings.dwarfDefaults();
        ArmorEditorSettings modded = ArmorEditorSettings.dwarfModdedDefaults();

        assertEquals(new ArmorEditorTransform(0.000f, 0.165f, 0.093f, 1.450f, 1.110f, 1.910f),
                vanilla.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(0.000f, 0.280f, -0.169f, 1.200f, 1.250f, 1.300f),
                modded.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.165f, 0.093f, 1.450f, 1.110f, 2.310f),
                modded.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(0.000f, 0.045f, 0.005f, 1.700f, 0.540f, 1.350f),
                modded.slotTransform(ArmorEditorSettings.SlotKey.LEGS));
        assertEquals(new ArmorEditorTransform(0.000f, -0.345f, 0.001f, 1.390f, 1.060f, 1.320f),
                modded.slotTransform(ArmorEditorSettings.SlotKey.FEET));
        assertEquals(new ArmorEditorTransform(-0.075f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f),
                vanilla.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.RIGHT_ARM));
        assertEquals(new ArmorEditorTransform(0.000f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f),
                modded.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.RIGHT_ARM));
        assertEquals(new ArmorEditorTransform(0.000f, -0.095f, -0.090f, 0.850f, 1.050f, 0.680f),
                modded.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.LEFT_ARM));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                vanilla.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.RIGHT_LEG));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                vanilla.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
        assertEquals(new ArmorEditorTransform(0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                modded.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.RIGHT_LEG));
        assertEquals(new ArmorEditorTransform(-0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                modded.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
    }

    @Test
    void moddedArmorDefaultsAreSeparateFromVanillaArmorDefaults() {
        ArmorEditorSettings vanilla = ArmorEditorSettings.defaults();
        ArmorEditorSettings modded = ArmorEditorSettings.moddedDefaults();

        assertEquals(new ArmorEditorTransform(0.000f, 0.025f, 0.005f, 1.000f, 1.250f, 1.000f),
                vanilla.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.025f, 0.005f, 1.000f, 1.250f, 1.000f),
                modded.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.012f, 1.000f, 1.060f, 1.270f),
                vanilla.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.002f, 1.000f, 1.060f, 1.270f),
                modded.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                vanilla.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.RIGHT_LEG));
        assertEquals(new ArmorEditorTransform(-0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                vanilla.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
        assertEquals(new ArmorEditorTransform(0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                modded.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.RIGHT_LEG));
        assertEquals(new ArmorEditorTransform(-0.050f, 0.000f, 0.000f, 1.030f, 1.030f, 1.030f),
                modded.partTransform(ArmorEditorSettings.SlotKey.LEGS, ArmorEditorSettings.PartKey.LEFT_LEG));
    }

    @Test
    void runtimeSettingsKeepArmorKindsIndependent() {
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.VANILLA, null);
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.MODDED, null);

        ArmorEditorSettings vanilla = ArmorEditorRuntimeSettings.snapshot(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.VANILLA);
        vanilla.setSlotTransform(ArmorEditorSettings.SlotKey.HEAD,
                new ArmorEditorTransform(0.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f));
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.VANILLA, vanilla);

        assertEquals(new ArmorEditorTransform(0.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f),
                ArmorEditorRuntimeSettings.snapshot(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.VANILLA)
                        .slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.025f, 0.005f, 1.000f, 1.250f, 1.000f),
                ArmorEditorRuntimeSettings.snapshot(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.MODDED)
                        .slotTransform(ArmorEditorSettings.SlotKey.HEAD));
    }

    @Test
    void runtimeSettingsKeepEveryModelArmorBucketFullyIndependent() {
        try {
            for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
                for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                    ArmorEditorRuntimeSettings.applyTransient(profile, armorKind, uniqueSettings(profile, armorKind));
                }
            }

            for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
                for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                    ArmorEditorSettings expected = uniqueSettings(profile, armorKind);
                    ArmorEditorSettings actual = ArmorEditorRuntimeSettings.snapshot(profile, armorKind);
                    assertSettingsEqual(expected, actual);
                }
            }
        } finally {
            for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
                for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                    ArmorEditorRuntimeSettings.applyTransient(profile, armorKind, null);
                }
            }
        }
    }

    @Test
    void exportsAllProfilesAndArmorKindsWithHeaders() {
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.VANILLA, null);
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.VILLAGER, ArmorEditorArmorKind.MODDED, null);
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.DWARF, ArmorEditorArmorKind.VANILLA, null);
        ArmorEditorRuntimeSettings.applyTransient(ArmorEditorProfile.DWARF, ArmorEditorArmorKind.MODDED, null);

        String export = ArmorEditorRuntimeSettings.exportAllHardcodeLines(null);

        assertTrue(export.contains("[VillagerOverhaul] Armor editor export all"));
        assertTrue(export.contains("[VillagerOverhaul] Armor editor export: VILLAGER / VANILLA"));
        assertTrue(export.contains("[VillagerOverhaul] Armor editor export: VILLAGER / MODDED"));
        assertTrue(export.contains("[VillagerOverhaul] Armor editor export: DWARF / VANILLA"));
        assertTrue(export.contains("[VillagerOverhaul] Armor editor export: DWARF / MODDED"));
        assertTrue(export.contains("part CHEST RIGHT_ARM = "));
        assertTrue(export.contains("part CHEST LEFT_ARM = "));
        assertTrue(export.contains("part LEGS RIGHT_LEG = "));
        assertTrue(export.contains("part LEGS LEFT_LEG = "));
        assertTrue(export.contains("part FEET RIGHT_LEG = "));
        assertTrue(export.contains("part FEET LEFT_LEG = "));
    }

    @Test
    void hardcodedDefaultsMatchCurrentEditorExportFixture() throws Exception {
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                ArmorEditorRuntimeSettings.applyTransient(profile, armorKind, null);
            }
        }

        String expected;
        try (InputStream in = ArmorEditorSettingsTest.class.getResourceAsStream("armor-editor-defaults-export.txt")) {
            if (in == null) throw new AssertionError("Missing armor editor export fixture");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String actual = ArmorEditorRuntimeSettings.exportAllHardcodeLines(null);

        assertEquals(normalizeLines(expected).trim(), normalizeLines(actual).trim());
    }

    private static ArmorEditorSettings uniqueSettings(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind) {
        ArmorEditorSettings settings = ArmorEditorSettings.defaults();
        int base = profile.ordinal() * 1000 + armorKind.ordinal() * 100;
        for (ArmorEditorSettings.SlotKey slot : ArmorEditorSettings.SlotKey.values()) {
            settings.setSlotTransform(slot, transform(base + slot.ordinal() * 10));
            for (ArmorEditorSettings.PartKey part : ArmorEditorSettings.PartKey.values()) {
                settings.setPartTransform(slot, part, transform(base + 100 + slot.ordinal() * 70 + part.ordinal() * 10));
            }
        }
        return settings;
    }

    private static ArmorEditorTransform transform(int seed) {
        float s = seed / 1000.0f;
        return new ArmorEditorTransform(s + 0.001f, s + 0.002f, s + 0.003f, s + 1.004f, s + 1.005f, s + 1.006f);
    }

    private static void assertSettingsEqual(ArmorEditorSettings expected, ArmorEditorSettings actual) {
        for (ArmorEditorSettings.SlotKey slot : ArmorEditorSettings.SlotKey.values()) {
            assertEquals(expected.slotTransform(slot), actual.slotTransform(slot), "slot " + slot.name());
            for (ArmorEditorSettings.PartKey part : ArmorEditorSettings.PartKey.values()) {
                assertEquals(expected.partTransform(slot, part), actual.partTransform(slot, part),
                        "part " + slot.name() + " " + part.name());
            }
        }
    }

    private static String normalizeLines(String text) {
        return (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
    }
}
