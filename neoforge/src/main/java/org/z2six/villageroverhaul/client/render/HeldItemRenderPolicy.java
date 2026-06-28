package org.z2six.villageroverhaul.client.render;

final class HeldItemRenderPolicy {
    private HeldItemRenderPolicy() {}

    static HeldItemRenderAnchor anchorFor(boolean dwarf, boolean customArmsFlag) {
        if (dwarf) return HeldItemRenderAnchor.DWARF_ARMS;
        return customArmsFlag ? HeldItemRenderAnchor.CUSTOM_ARMS : HeldItemRenderAnchor.NONE;
    }
}
