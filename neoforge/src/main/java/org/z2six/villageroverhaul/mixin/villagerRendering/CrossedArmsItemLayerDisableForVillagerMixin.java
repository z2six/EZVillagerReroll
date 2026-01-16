// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/villagerRendering/CrossedArmsItemLayerDisableForVillagerMixin.java
package org.z2six.villageroverhaul.mixin.villagerRendering;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

/**
 * Extra safety net: cancel vanilla villager chest-held-item layers when VillagerBrain says custom arms.
 *
 * We use string targets so we can cover multiple possible class names without compile-time deps.
 * (Your old reflection remover already implied subclasses / different names exist.)
 *
 * NOTE:
 * The primary/most-correct solution is the wrapper in ClientRenderEvents (per-entity gating).
 * This mixin is purely redundancy in case Mojang moves logic around again.
 */
@Mixin(targets = {
        "net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer",
        "net.minecraft.client.renderer.entity.layers.VillagerItemLayer",
        "net.minecraft.client.renderer.entity.layers.VillagerHeldItemLayer"
})
public abstract class CrossedArmsItemLayerDisableForVillagerMixin {

    // Signature variant: (PoseStack, MultiBufferSource, int, Villager, floats...)
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/npc/Villager;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$disableForVillager(PoseStack poseStack,
                                         MultiBufferSource buffer,
                                         int packedLight,
                                         Villager villager,
                                         float limbSwing,
                                         float limbSwingAmount,
                                         float partialTick,
                                         float ageInTicks,
                                         float netHeadYaw,
                                         float headPitch,
                                         CallbackInfo ci) {
        try {
            if (villager == null) return;

            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            // If custom arms => do NOT render vanilla crossed-arms chest item layer
            if (!VillagerRenderFlags.renderVanillaCrossedArmsItemLayer(flags)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }

    // Signature variant: (PoseStack, MultiBufferSource, int, LivingEntity, floats...)
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$disableForLiving(PoseStack poseStack,
                                       MultiBufferSource buffer,
                                       int packedLight,
                                       LivingEntity entity,
                                       float limbSwing,
                                       float limbSwingAmount,
                                       float partialTick,
                                       float ageInTicks,
                                       float netHeadYaw,
                                       float headPitch,
                                       CallbackInfo ci) {
        try {
            if (!(entity instanceof Villager villager)) return;

            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            if (!VillagerRenderFlags.renderVanillaCrossedArmsItemLayer(flags)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
