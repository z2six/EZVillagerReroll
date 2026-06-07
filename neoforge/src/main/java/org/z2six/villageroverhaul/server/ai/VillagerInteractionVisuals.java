package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.item.ItemStack;

final class VillagerInteractionVisuals {

    private VillagerInteractionVisuals() {}

    static boolean shouldCreateFallbackVisualForEmptyInteractHand() {
        return false;
    }

    static ItemStack mainHandVisualForInteract(ItemStack currentMainHand) {
        if (currentMainHand == null || currentMainHand.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return currentMainHand.copy();
    }
}
