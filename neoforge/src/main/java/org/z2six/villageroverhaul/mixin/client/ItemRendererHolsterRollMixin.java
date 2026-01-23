// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/client/ItemRendererHolsterRollMixin.java
package org.z2six.villageroverhaul.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.client.render.HolsterItemRenderTweakState;

/**
 * Applies a post-item-transform "roll" for holstered loadout items.
 *
 * This is intentionally done at the deepest common draw helper so the roll is applied AFTER
 * ItemDisplayContext transforms (so you can twist around the item's own axis).
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererHolsterRollMixin {

    @Inject(
            method = "renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderModelListsHead1(BakedModel bakedModel,
                                           ItemStack itemStack,
                                           int packedLight,
                                           int packedOverlay,
                                           PoseStack poseStack,
                                           VertexConsumer vertexConsumer,
                                           CallbackInfo ci) {
        apply(poseStack);
    }

    @Inject(
            method = "renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Z)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderModelListsHead2(BakedModel bakedModel,
                                           ItemStack itemStack,
                                           int packedLight,
                                           int packedOverlay,
                                           PoseStack poseStack,
                                           VertexConsumer vertexConsumer,
                                           boolean glint,
                                           CallbackInfo ci) {
        apply(poseStack);
    }

    private static void apply(PoseStack poseStack) {
        try {
            if (poseStack == null) return;
            if (!HolsterItemRenderTweakState.active()) return;

            float deg = HolsterItemRenderTweakState.rollDeg();
            if (deg == 0.0f) return;

            switch (HolsterItemRenderTweakState.rollAxis()) {
                case X -> poseStack.mulPose(Axis.XP.rotationDegrees(deg));
                case Y -> poseStack.mulPose(Axis.YP.rotationDegrees(deg));
                case Z -> poseStack.mulPose(Axis.ZP.rotationDegrees(deg));
            }
        } catch (Throwable ignored) {}
    }
}
