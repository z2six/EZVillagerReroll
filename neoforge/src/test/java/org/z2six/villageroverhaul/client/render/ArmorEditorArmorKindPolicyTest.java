package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

final class ArmorEditorArmorKindPolicyTest {

    @Test
    void classifiesMinecraftNamespaceArmorAsVanilla() {
        assertEquals(ArmorEditorArmorKind.VANILLA, ArmorEditorArmorKindPolicy.armorKindForItemId("minecraft:diamond_helmet"));
        assertEquals(ArmorEditorArmorKind.MODDED, ArmorEditorArmorKindPolicy.armorKindForItemId("immersive_armors:steampunk_helmet"));
        assertEquals(ArmorEditorArmorKind.MODDED, ArmorEditorArmorKindPolicy.armorKindForItemId("diamond_helmet"));
    }

    @Test
    void filtersArmorIdsByArmorKind() {
        List<String> ids = List.of(
                "minecraft:diamond_helmet",
                "immersive_armors:steampunk_helmet",
                "minecraft:iron_chestplate",
                "armoroftheages:anubis_armor_feet"
        );

        assertEquals(List.of("minecraft:diamond_helmet", "minecraft:iron_chestplate"),
                ArmorEditorArmorKindPolicy.filterArmorIdsForKind(ids, ArmorEditorArmorKind.VANILLA));
        assertEquals(List.of("immersive_armors:steampunk_helmet", "armoroftheages:anubis_armor_feet"),
                ArmorEditorArmorKindPolicy.filterArmorIdsForKind(ids, ArmorEditorArmorKind.MODDED));
    }
}
