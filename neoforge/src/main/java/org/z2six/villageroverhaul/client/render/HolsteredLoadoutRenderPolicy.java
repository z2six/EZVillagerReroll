package org.z2six.villageroverhaul.client.render;

public final class HolsteredLoadoutRenderPolicy {
    private HolsteredLoadoutRenderPolicy() {}

    public static boolean shouldRenderWhileHandsEmpty(boolean dwarf, boolean customArmsFlag) {
        return dwarf || !customArmsFlag;
    }
}
