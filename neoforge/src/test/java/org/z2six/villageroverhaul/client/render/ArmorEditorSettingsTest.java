package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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

        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.005f, 1.000f, 1.200f, 1.000f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.012f, 1.000f, 1.060f, 1.270f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.CHEST));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 0.930f, 1.060f, 1.180f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.LEGS));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.006f, 0.960f, 1.060f, 0.920f),
                settings.slotTransform(ArmorEditorSettings.SlotKey.FEET));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.000f, 1.000f, 1.000f),
                settings.partTransform(ArmorEditorSettings.SlotKey.HEAD, ArmorEditorSettings.PartKey.HEAD));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.070f, 1.070f, 1.070f),
                settings.partTransform(ArmorEditorSettings.SlotKey.CHEST, ArmorEditorSettings.PartKey.BODY));
        assertEquals(new ArmorEditorTransform(0.000f, 0.000f, 0.000f, 1.020f, 1.020f, 1.020f),
                settings.partTransform(ArmorEditorSettings.SlotKey.FEET, ArmorEditorSettings.PartKey.LEFT_LEG));
    }
}
