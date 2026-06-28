package org.z2six.villageroverhaul.mixin.villagerRendering;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.server.VillagerFactionService;

@Mixin(RenderLayer.class)
public abstract class RenderLayerReleaseFadeMixin {

    @Unique
    private static final ThreadLocal<LivingEntity> EZVR_LAYER_ENTITY = new ThreadLocal<>();

    @Inject(
            method = "renderColoredCutoutModel",
            at = @At("HEAD"),
            require = 0
    )
    private static <T extends LivingEntity> void ezvr$releaseFadeLayerHead(EntityModel<T> model,
                                                                            ResourceLocation textureLocation,
                                                                            PoseStack poseStack,
                                                                            MultiBufferSource buffer,
                                                                            int packedLight,
                                                                            T entity,
                                                                            int color,
                                                                            CallbackInfo ci) {
        EZVR_LAYER_ENTITY.set(entity);
    }

    @Inject(
            method = "renderColoredCutoutModel",
            at = @At("RETURN"),
            require = 0
    )
    private static <T extends LivingEntity> void ezvr$releaseFadeLayerReturn(EntityModel<T> model,
                                                                              ResourceLocation textureLocation,
                                                                              PoseStack poseStack,
                                                                              MultiBufferSource buffer,
                                                                              int packedLight,
                                                                              T entity,
                                                                              int color,
                                                                              CallbackInfo ci) {
        EZVR_LAYER_ENTITY.remove();
    }

    @Redirect(
            method = "renderColoredCutoutModel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;entityCutoutNoCull(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0
    )
    private static RenderType ezvr$releaseFadeLayerRenderType(ResourceLocation textureLocation) {
        if (ezvr$releaseAlpha(EZVR_LAYER_ENTITY.get()) < 255) {
            return RenderType.itemEntityTranslucentCull(textureLocation);
        }
        return RenderType.entityCutoutNoCull(textureLocation);
    }

    @Redirect(
            method = "renderColoredCutoutModel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            require = 0
    )
    private static <T extends LivingEntity> void ezvr$releaseFadeLayerRender(EntityModel<T> model,
                                                                              PoseStack poseStack,
                                                                              com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer,
                                                                              int packedLight,
                                                                              int packedOverlay,
                                                                              int color,
                                                                              EntityModel<T> originalModel,
                                                                              ResourceLocation textureLocation,
                                                                              PoseStack originalPoseStack,
                                                                              MultiBufferSource buffer,
                                                                              int originalPackedLight,
                                                                              T entity,
                                                                              int originalColor) {
        EZVR_LAYER_ENTITY.set(entity);
        int alpha = ezvr$releaseAlpha(entity);
        if (VillagerFactionService.isDwarf(entity)) {
            return;
        }
        int packed = color;
        if (alpha < 255) {
            int rgb = color & 0x00FFFFFF;
            if (rgb == 0) rgb = 0x00FFFFFF;
            packed = ((alpha & 0xFF) << 24) | rgb;
        }
        model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay, packed);
    }

    @Unique
    private static int ezvr$releaseAlpha(LivingEntity entity) {
        try {
            if (!(entity instanceof Villager vill)) return 255;
            if (!(vill instanceof VillagerOverhaulRenderAccess acc)) return 255;
            return Byte.toUnsignedInt(acc.ezvr$getReleaseAlpha());
        } catch (Throwable ignored) {
            return 255;
        }
    }
}
