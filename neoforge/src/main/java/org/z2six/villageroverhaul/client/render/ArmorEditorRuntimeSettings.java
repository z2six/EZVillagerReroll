package org.z2six.villageroverhaul.client.render;

import net.minecraft.world.entity.EquipmentSlot;

import java.util.EnumMap;

public final class ArmorEditorRuntimeSettings {
    private static final EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> TRANSIENT_SETTINGS =
            new EnumMap<>(ArmorEditorProfile.class);
    private static final ThreadLocal<ArmorEditorProfile> ACTIVE_PROFILE =
            ThreadLocal.withInitial(() -> ArmorEditorProfile.VILLAGER);
    private static final ThreadLocal<ArmorEditorArmorKind> ACTIVE_ARMOR_KIND =
            ThreadLocal.withInitial(() -> ArmorEditorArmorKind.VANILLA);
    private static final ThreadLocal<Integer> ARMOR_KIND_OVERRIDE_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static volatile boolean hideDwarfBodyShapeWithChest = true;

    private ArmorEditorRuntimeSettings() {}

    public static synchronized ArmorEditorSettings snapshot() {
        return snapshot(ArmorEditorProfile.VILLAGER);
    }

    public static synchronized ArmorEditorSettings snapshot(ArmorEditorProfile profile) {
        return snapshot(profile, ArmorEditorArmorKind.VANILLA);
    }

    public static synchronized ArmorEditorSettings snapshot(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind) {
        return currentSettings(profile, armorKind).copy();
    }

    public static synchronized void applyTransient(ArmorEditorSettings next) {
        applyTransient(ArmorEditorProfile.VILLAGER, next);
    }

    public static synchronized void applyTransient(ArmorEditorProfile profile, ArmorEditorSettings next) {
        applyTransient(profile, ArmorEditorArmorKind.VANILLA, next);
    }

    public static synchronized void applyTransient(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind, ArmorEditorSettings next) {
        ArmorEditorSettings copy = next == null ? null : next.copy();
        ArmorEditorProfile p = normalizeProfile(profile);
        ArmorEditorArmorKind k = normalizeArmorKind(armorKind);
        EnumMap<ArmorEditorArmorKind, ArmorEditorSettings> byKind = TRANSIENT_SETTINGS.computeIfAbsent(p, ignored -> new EnumMap<>(ArmorEditorArmorKind.class));
        if (copy == null) {
            byKind.remove(k);
        } else {
            byKind.put(k, copy);
        }
    }

    public static synchronized void save(ArmorEditorSettings next) {
        save(ArmorEditorProfile.VILLAGER, next);
    }

    public static synchronized void save(ArmorEditorProfile profile, ArmorEditorSettings next) {
        applyTransient(profile, next);
    }

    public static synchronized void save(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind, ArmorEditorSettings next) {
        applyTransient(profile, armorKind, next);
    }

    public static synchronized String exportHardcodeLines() {
        return exportAllHardcodeLines(null);
    }

    public interface ExportSettingsProvider {
        ArmorEditorSettings settings(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind);
    }

    public static synchronized String exportAllHardcodeLines(ExportSettingsProvider provider) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("[VillagerOverhaul] Armor editor export all\n");
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                ArmorEditorSettings settings = provider == null ? null : provider.settings(profile, armorKind);
                if (settings == null) settings = currentSettings(profile, armorKind);
                sb.append('\n');
                sb.append(settings.exportHardcodeLines("[VillagerOverhaul] Armor editor export: "
                        + profile.name() + " / " + armorKind.name()));
            }
        }
        return sb.toString();
    }

    public static ArmorEditorTransform slotTransform(EquipmentSlot slot) {
        return slotTransform(activeProfile(), slot);
    }

    public static ArmorEditorTransform slotTransform(ArmorEditorProfile profile, EquipmentSlot slot) {
        return slotTransform(profile, activeArmorKind(), slot);
    }

    public static ArmorEditorTransform slotTransform(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind, EquipmentSlot slot) {
        ArmorEditorSettings.SlotKey key = slotKey(slot);
        if (key == null) return ArmorEditorTransform.IDENTITY;
        synchronized (ArmorEditorRuntimeSettings.class) {
            return currentSettings(profile, armorKind).slotTransform(key);
        }
    }

    public static ArmorEditorTransform partTransform(EquipmentSlot slot, ArmorEditorSettings.PartKey part) {
        return partTransform(activeProfile(), slot, part);
    }

    public static ArmorEditorTransform partTransform(ArmorEditorProfile profile, EquipmentSlot slot, ArmorEditorSettings.PartKey part) {
        return partTransform(profile, activeArmorKind(), slot, part);
    }

    public static ArmorEditorTransform partTransform(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind, EquipmentSlot slot, ArmorEditorSettings.PartKey part) {
        ArmorEditorSettings.SlotKey key = slotKey(slot);
        if (key == null || part == null) return ArmorEditorTransform.IDENTITY;
        synchronized (ArmorEditorRuntimeSettings.class) {
            return currentSettings(profile, armorKind).partTransform(key, part);
        }
    }

    public static ArmorEditorProfile activeProfile() {
        ArmorEditorProfile profile = ACTIVE_PROFILE.get();
        return normalizeProfile(profile);
    }

    public static ArmorEditorArmorKind activeArmorKind() {
        return normalizeArmorKind(ACTIVE_ARMOR_KIND.get());
    }

    public static boolean armorKindOverrideActive() {
        Integer depth = ARMOR_KIND_OVERRIDE_DEPTH.get();
        return depth != null && depth > 0;
    }

    public static Scope pushProfile(ArmorEditorProfile profile) {
        ArmorEditorProfile previous = activeProfile();
        ACTIVE_PROFILE.set(normalizeProfile(profile));
        return new Scope(previous, activeArmorKind());
    }

    public static Scope pushArmorKind(ArmorEditorArmorKind armorKind) {
        ArmorEditorArmorKind previous = activeArmorKind();
        ACTIVE_ARMOR_KIND.set(normalizeArmorKind(armorKind));
        ARMOR_KIND_OVERRIDE_DEPTH.set(Math.max(0, ARMOR_KIND_OVERRIDE_DEPTH.get()) + 1);
        return new Scope(activeProfile(), previous, true);
    }

    public static Scope pushDetectedArmorKind(ArmorEditorArmorKind armorKind) {
        ArmorEditorArmorKind previous = activeArmorKind();
        ACTIVE_ARMOR_KIND.set(normalizeArmorKind(armorKind));
        return new Scope(activeProfile(), previous, false);
    }

    public static boolean hideDwarfBodyShapeWithChest() {
        return hideDwarfBodyShapeWithChest;
    }

    public static void setHideDwarfBodyShapeWithChest(boolean hide) {
        hideDwarfBodyShapeWithChest = hide;
    }

    public static final class Scope implements AutoCloseable {
        private final ArmorEditorProfile previous;
        private final ArmorEditorArmorKind previousArmorKind;
        private final boolean armorKindOverride;
        private boolean closed;

        private Scope(ArmorEditorProfile previous, ArmorEditorArmorKind previousArmorKind) {
            this(previous, previousArmorKind, false);
        }

        private Scope(ArmorEditorProfile previous, ArmorEditorArmorKind previousArmorKind, boolean armorKindOverride) {
            this.previous = normalizeProfile(previous);
            this.previousArmorKind = normalizeArmorKind(previousArmorKind);
            this.armorKindOverride = armorKindOverride;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            ACTIVE_PROFILE.set(previous);
            ACTIVE_ARMOR_KIND.set(previousArmorKind);
            if (armorKindOverride) {
                ARMOR_KIND_OVERRIDE_DEPTH.set(Math.max(0, ARMOR_KIND_OVERRIDE_DEPTH.get() - 1));
            }
        }
    }

    public static ArmorEditorSettings.SlotKey slotKey(EquipmentSlot slot) {
        if (slot == null) return null;
        return switch (slot) {
            case HEAD -> ArmorEditorSettings.SlotKey.HEAD;
            case CHEST -> ArmorEditorSettings.SlotKey.CHEST;
            case LEGS -> ArmorEditorSettings.SlotKey.LEGS;
            case FEET -> ArmorEditorSettings.SlotKey.FEET;
            default -> null;
        };
    }

    private static ArmorEditorSettings currentSettings(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind) {
        ArmorEditorProfile p = normalizeProfile(profile);
        ArmorEditorArmorKind k = normalizeArmorKind(armorKind);
        EnumMap<ArmorEditorArmorKind, ArmorEditorSettings> byKind = TRANSIENT_SETTINGS.get(p);
        ArmorEditorSettings settings = byKind == null ? null : byKind.get(k);
        if (settings != null) return settings;
        if (p == ArmorEditorProfile.DWARF) {
            return k == ArmorEditorArmorKind.MODDED
                    ? ArmorEditorSettings.dwarfModdedDefaults()
                    : ArmorEditorSettings.dwarfDefaults();
        }
        return k == ArmorEditorArmorKind.MODDED
                ? ArmorEditorSettings.moddedDefaults()
                : ArmorEditorSettings.defaults();
    }

    private static ArmorEditorProfile normalizeProfile(ArmorEditorProfile profile) {
        return profile == null ? ArmorEditorProfile.VILLAGER : profile;
    }

    private static ArmorEditorArmorKind normalizeArmorKind(ArmorEditorArmorKind armorKind) {
        return armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind;
    }
}
