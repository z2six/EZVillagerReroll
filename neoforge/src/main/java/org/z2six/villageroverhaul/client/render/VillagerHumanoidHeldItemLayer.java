// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHumanoidHeldItemLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

public final class VillagerHumanoidHeldItemLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    // =========================================================================================
    // TWEAKS (if alignment feels off)
    // =========================================================================================

    private static final boolean ENABLE_ITEM_TRANSFORM = true;

    /** Extra translation after translateToHand() */
    private static final float ITX = 0.0f;
    private static final float ITY = 0.0f;
    private static final float ITZ = 0.0f;

    /** Extra rotation after translateToHand() */
    private static final float IRX_DEG = -90.0f;
    private static final float IRY_DEG = 180.0f;
    private static final float IRZ_DEG = 0.0f;

    /** Uniform scale tweak */
    private static final float ISCALE = 1.0f;

    // =========================================================================================

    private final VillagerCombatArmsModel armsModel;

    public VillagerHumanoidHeldItemLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                         VillagerCombatArmsModel armsModel) {
        super(parent);
        this.armsModel = armsModel;
    }

    @Override
    public void render(PoseStack poseStack,
                       MultiBufferSource buffer,
                       int packedLight,
                       Villager villager,
                       float limbSwing,
                       float limbSwingAmount,
                       float partialTick,
                       float ageInTicks,
                       float netHeadYaw,
                       float headPitch) {
        try {
            if (villager == null || poseStack == null || buffer == null) return;
            if (armsModel == null) return;

            // Only render held items when we are using custom humanoid arms
            if (!shouldRenderCustomArms(villager)) return;

            ItemStack main = villager.getMainHandItem();
            ItemStack off  = villager.getOffhandItem();

            if (main != null && !main.isEmpty()) {
                HumanoidArm mainArm = villager.getMainArm();
                renderOneHand(villager, main, mainArm, poseStack, buffer, packedLight);
            }

            if (off != null && !off.isEmpty()) {
                HumanoidArm offArm = (villager.getMainArm() == HumanoidArm.RIGHT) ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
                renderOneHand(villager, off, offArm, poseStack, buffer, packedLight);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHumanoidHeldItemLayer.render failed (soft): {}", t.toString());
        }
    }

    private void renderOneHand(Villager villager,
                               ItemStack stack,
                               HumanoidArm arm,
                               PoseStack poseStack,
                               MultiBufferSource buffer,
                               int packedLight) {
        try {
            if (stack == null || stack.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            var itemRenderer = mc.getItemRenderer();
            if (itemRenderer == null) return;

            // Which hand transform to use
            ItemDisplayContext ctx = (arm == HumanoidArm.RIGHT)
                    ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;

            poseStack.pushPose();
            try {
                // Move to the villager's animated hand pivot (custom arms)
                armsModel.translateToHand(arm, poseStack);

                if (ENABLE_ITEM_TRANSFORM) {
                    poseStack.translate(ITX, ITY, ITZ);
                    poseStack.mulPose(Axis.XP.rotationDegrees(IRX_DEG));
                    poseStack.mulPose(Axis.YP.rotationDegrees(IRY_DEG));
                    if (IRZ_DEG != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(IRZ_DEG));
                    if (ISCALE != 1.0f) poseStack.scale(ISCALE, ISCALE, ISCALE);
                }

                // Render the item model (weapon/tool/etc) in third-person hand context
                // Signature is stable in 1.21.x MojMap:
                // renderStatic(entity, stack, context, leftHand, poseStack, buffer, level, light, overlay, seed)
                boolean leftHand = (arm == HumanoidArm.LEFT);

                itemRenderer.renderStatic(
                        villager,
                        stack,
                        ctx,
                        leftHand,
                        poseStack,
                        buffer,
                        villager.level(),
                        packedLight,
                        OverlayTexture.NO_OVERLAY,
                        villager.getId()
                );

            } finally {
                poseStack.popPose();
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] renderOneHand failed (soft): {}", t.toString());
        }
    }

    private static boolean shouldRenderCustomArms(Villager v) {
        try {
            if (v instanceof VillagerOverhaulRenderAccess acc) {
                return VillagerRenderFlags.renderCustomArms(acc.ezvr$getRenderFlags());
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
