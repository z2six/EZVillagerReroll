package org.z2six.villageroverhaul.server.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class VillagerInteractionVisualsTest {

    @Test
    void emptyInteractHandDoesNotCreateFallbackVisualItem() {
        assertFalse(VillagerInteractionVisuals.shouldCreateFallbackVisualForEmptyInteractHand());
    }
}
