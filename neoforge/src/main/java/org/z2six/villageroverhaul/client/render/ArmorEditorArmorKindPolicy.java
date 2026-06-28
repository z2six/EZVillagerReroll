package org.z2six.villageroverhaul.client.render;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ArmorEditorArmorKindPolicy {
    private ArmorEditorArmorKindPolicy() {
    }

    public static ArmorEditorArmorKind armorKindForItemId(String id) {
        String s = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("minecraft:") ? ArmorEditorArmorKind.VANILLA : ArmorEditorArmorKind.MODDED;
    }

    public static List<String> filterArmorIdsForKind(List<String> ids, ArmorEditorArmorKind armorKind) {
        ArmorEditorArmorKind kind = armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind;
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .filter(id -> armorKindForItemId(id) == kind)
                .collect(Collectors.toList());
    }
}
