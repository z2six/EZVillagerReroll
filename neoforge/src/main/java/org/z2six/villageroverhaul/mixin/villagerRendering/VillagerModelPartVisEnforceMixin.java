// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/villagerRendering/VillagerModelPartVisEnforceMixin.java
package org.z2six.villageroverhaul.mixin.villagerRendering;

import net.minecraft.client.model.VillagerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.client.render.ClientPartVisibilityRules;

/**
 * Re-applies part visibility rules every frame AFTER vanilla has run its animation/setup,
 * because vanilla resets visibility (head/hat especially) each tick.
 */
@Mixin(VillagerModel.class)
public abstract class VillagerModelPartVisEnforceMixin {

    // Newer pipeline (render state) - if present, we hook it.
    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;)V",
            at = @At("TAIL"),
            require = 0
    )
    private void vo$partvis_setupAnim_state_tail(Object state, CallbackInfo ci) {
        if (!ClientPartVisibilityRules.hasAny()) return;
        ClientPartVisibilityRules.applyToModel(this);
    }

    // Older signature (entity + floats) - if present, we hook it too.
    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/npc/Villager;FFFFF)V",
            at = @At("TAIL"),
            require = 0
    )
    private void vo$partvis_setupAnim_entity_tail(Object villager, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!ClientPartVisibilityRules.hasAny()) return;
        ClientPartVisibilityRules.applyToModel(this);
    }

    // Vanilla uses hatVisible(...) to re-toggle hat/hat_rim etc.
    @Inject(
            method = "hatVisible(Z)V",
            at = @At("TAIL"),
            require = 0
    )
    private void vo$partvis_hatVisible_tail(boolean visible, CallbackInfo ci) {
        if (!ClientPartVisibilityRules.hasAny()) return;
        ClientPartVisibilityRules.applyToModel(this);
    }
}
