// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/ItemInHandLayerDisableForVillagerMixin.java
package org.z2six.villageroverhaul.mixin.villagerRendering;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerDisableForVillagerMixin {

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void ezvr$disableVanillaHeldItemsForVillager(PoseStack poseStack,
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
            if (!(entity instanceof Villager vill)) return;

            if (vill instanceof VillagerOverhaulRenderAccess acc) {
                if (VillagerRenderFlags.renderCustomArms(acc.ezvr$getRenderFlags())) {
                    // We render held items ourselves, anchored to custom arms.
                    ci.cancel();
                }
            }
        } catch (Throwable ignored) {}
    }
}
