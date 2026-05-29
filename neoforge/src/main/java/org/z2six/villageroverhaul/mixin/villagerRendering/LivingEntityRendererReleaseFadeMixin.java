package org.z2six.villageroverhaul.mixin.villagerRendering;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererReleaseFadeMixin<T extends LivingEntity> {

    @Unique
    private static final ThreadLocal<LivingEntity> EZVR_RENDERING = new ThreadLocal<>();

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void ezvr$releaseFadeRenderHead(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                            MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        EZVR_RENDERING.set(entity);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void ezvr$releaseFadeRenderReturn(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                              MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        EZVR_RENDERING.remove();
    }

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;"),
            index = 1,
            require = 0
    )
    private boolean ezvr$releaseFadeBodyVisible(boolean original) {
        return ezvr$releaseAlpha(EZVR_RENDERING.get()) < 255 ? false : original;
    }

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;"),
            index = 2,
            require = 0
    )
    private boolean ezvr$releaseFadeTranslucent(boolean original) {
        return ezvr$releaseAlpha(EZVR_RENDERING.get()) < 255 ? true : original;
    }

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
            index = 4,
            require = 0
    )
    private int ezvr$releaseFadePackedColor(int original) {
        int alpha = ezvr$releaseAlpha(EZVR_RENDERING.get());
        if (alpha >= 255) return original;
        int rgb = original & 0x00FFFFFF;
        if (rgb == 0) rgb = 0x00FFFFFF;
        return ((alpha & 0xFF) << 24) | rgb;
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
