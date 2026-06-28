package org.z2six.villageroverhaul.client.render;

public final class ArmorArmAnimationPolicy {
    private ArmorArmAnimationPolicy() {
    }

    public static boolean shouldAnimateArmorArms(boolean dwarf, boolean renderPseudoArms) {
        return dwarf || renderPseudoArms;
    }
}
