package org.z2six.villageroverhaul.client.render;

import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.UseAnim;

import java.util.EnumMap;
import java.util.Locale;

public final class WeaponEditorState {
    public enum HeldProfile {
        GENERIC,
        SHIELD,
        BOW,
        CROSSBOW
    }

    private static final EnumMap<ArmorEditorProfile, EnumMap<HeldProfile, WeaponEditorTransform>> HELD =
            new EnumMap<>(ArmorEditorProfile.class);

    static {
        resetAllHeld();
    }

    private WeaponEditorState() {}

    public static HeldProfile heldProfileFor(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return HeldProfile.GENERIC;
            if (stack.getItem() instanceof CrossbowItem) return HeldProfile.CROSSBOW;
            if (stack.getItem() instanceof BowItem) return HeldProfile.BOW;
            UseAnim anim = stack.getUseAnimation();
            if (anim == UseAnim.BLOCK) return HeldProfile.SHIELD;
            if (stack.getItem() instanceof ProjectileWeaponItem) {
                if (anim == UseAnim.CROSSBOW) return HeldProfile.CROSSBOW;
                if (anim == UseAnim.BOW) return HeldProfile.BOW;
                try {
                    if (stack.useOnRelease()) return HeldProfile.CROSSBOW;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return HeldProfile.GENERIC;
    }

    public static WeaponEditorTransform heldTransform(ArmorEditorProfile profile, HeldProfile heldProfile) {
        EnumMap<HeldProfile, WeaponEditorTransform> byProfile = HELD.get(normalizeProfile(profile));
        if (byProfile == null) return defaultHeldTransform(heldProfile);
        WeaponEditorTransform tx = byProfile.get(normalizeHeldProfile(heldProfile));
        return tx == null ? defaultHeldTransform(heldProfile) : tx;
    }

    public static void setHeldTransform(ArmorEditorProfile profile, HeldProfile heldProfile, WeaponEditorTransform transform) {
        if (transform == null) return;
        HELD.computeIfAbsent(normalizeProfile(profile), ignored -> new EnumMap<>(HeldProfile.class))
                .put(normalizeHeldProfile(heldProfile), transform);
    }

    public static void setHeldParam(ArmorEditorProfile profile, HeldProfile heldProfile, String key, float value, boolean additive) {
        HeldProfile hp = normalizeHeldProfile(heldProfile);
        setHeldTransform(profile, hp, heldTransform(profile, hp).with(key, value, additive));
    }

    public static void resetHeld(ArmorEditorProfile profile, HeldProfile heldProfile) {
        setHeldTransform(profile, heldProfile, defaultHeldTransform(heldProfile));
    }

    public static void resetAllHeld() {
        HELD.clear();
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            EnumMap<HeldProfile, WeaponEditorTransform> byProfile = new EnumMap<>(HeldProfile.class);
            for (HeldProfile heldProfile : HeldProfile.values()) {
                byProfile.put(heldProfile, defaultHeldTransform(heldProfile));
            }
            HELD.put(profile, byProfile);
        }
    }

    public static WeaponEditorTransform defaultHeldTransform(HeldProfile heldProfile) {
        return switch (normalizeHeldProfile(heldProfile)) {
            case GENERIC -> new WeaponEditorTransform(0.0f, 0.0f, -0.05f, -90.0f, 180.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            case SHIELD -> new WeaponEditorTransform(0.0f, 0.0f, -0.05f, -90.0f, 180.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            case BOW -> new WeaponEditorTransform(0.0f, 0.0f, -0.05f, -90.0f, 180.0f, 0.0f, 1.0f, 1.0f, 1.0f);
            case CROSSBOW -> new WeaponEditorTransform(0.0f, 0.0f, -0.05f, -90.0f, 180.0f, 0.0f, 1.0f, 1.0f, 1.0f);
        };
    }

    public static String heldHardcodeLines() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("[VillagerOverhaul] Weapon editor held export\n");
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            for (HeldProfile heldProfile : HeldProfile.values()) {
                sb.append("held ")
                        .append(profile.name())
                        .append(' ')
                        .append(heldProfile.name())
                        .append(" = ")
                        .append(heldTransform(profile, heldProfile).toHardcode())
                        .append(";\n");
            }
        }
        return sb.toString();
    }

    public static String heldLine(ArmorEditorProfile profile, HeldProfile heldProfile) {
        return String.format(Locale.ROOT, "%s / %s: %s",
                normalizeProfile(profile).name(),
                normalizeHeldProfile(heldProfile).name(),
                heldTransform(profile, heldProfile).compact());
    }

    private static ArmorEditorProfile normalizeProfile(ArmorEditorProfile profile) {
        return profile == null ? ArmorEditorProfile.VILLAGER : profile;
    }

    private static HeldProfile normalizeHeldProfile(HeldProfile profile) {
        return profile == null ? HeldProfile.GENERIC : profile;
    }
}
