package org.z2six.villageroverhaul.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class HeldItemRenderPolicyTest {
    @Test
    void dwarvesUseTheirOwnArmsEvenWhenCustomArmsFlagIsSet() {
        assertEquals(HeldItemRenderAnchor.DWARF_ARMS, HeldItemRenderPolicy.anchorFor(true, true));
    }

    @Test
    void normalVillagersUseCustomArmsOnlyWhenFlagIsSet() {
        assertEquals(HeldItemRenderAnchor.CUSTOM_ARMS, HeldItemRenderPolicy.anchorFor(false, true));
        assertEquals(HeldItemRenderAnchor.NONE, HeldItemRenderPolicy.anchorFor(false, false));
    }
}
