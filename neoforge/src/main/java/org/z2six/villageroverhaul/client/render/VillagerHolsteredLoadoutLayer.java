// neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHolsteredLoadoutLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Method;

/**
 * Renders the villager's combat loadout cosmetically while the villager is NOT actively holding it.
 *
 * Desired look:
 * - Main loadout item (e.g. sword) on the waist.
 * - Offhand loadout item (e.g. shield) on the back.
 *
 * Source of truth for the item stacks is server -> client SynchedEntityData (see {code VillagerRenderStateMixin}).
 */
public final class VillagerHolsteredLoadoutLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    // =========================================================================================
    // TWEAKS (alignment)
    // ModelPart#translateAndRotate uses 1/16 scaling, so small numbers matter.
    // =========================================================================================

    private static final boolean ENABLE_WAIST = true;
    private static final float WAIST_TX = 0.25f;
    private static final float WAIST_TY = 0.62f;
    private static final float WAIST_TZ = 0.10f;
    private static final float WAIST_RX_DEG = -85.0f; // up/down
    private static final float WAIST_RY_DEG = 45.0f; // dont touch
    private static final float WAIST_RZ_DEG = 90.0f;
    private static final float WAIST_SCALE = 1.00f;

    private static final boolean ENABLE_BACK = true;
    private static final float BACK_TX = 0.20f; // left/right back perspective
    private static final float BACK_TY = 0.20f; // up/down from back perspective
    private static final float BACK_TZ = 0.15f; // inwards/outwards back perspective
    private static final float BACK_RX_DEG = 180.0f;
    private static final float BACK_RY_DEG = 270.0f;
    private static final float BACK_RZ_DEG = 0.0f;
    private static final float BACK_SCALE = 0.75f;

    // =========================================================================================

    private boolean ezvr$initLogged = false;
    private static Method ROOT_METHOD = null;

    public VillagerHolsteredLoadoutLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent) {
        super(parent);
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

            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VillagerHolsteredLoadoutLayer ACTIVE");
            }

            // If the villager is holding anything, let the held-item layers handle it.
            ItemStack heldMain = villager.getMainHandItem();
            ItemStack heldOff = villager.getOffhandItem();
            if ((heldMain != null && !heldMain.isEmpty()) || (heldOff != null && !heldOff.isEmpty())) return;

            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            // Only show holstered loadout while using vanilla crossed arms (i.e., NOT custom arms).
            if (VillagerRenderFlags.renderCustomArms(flags)) return;

            if (!(villager instanceof VillagerOverhaulRenderAccess acc)) return;
            ItemStack loadoutMain = acc.ezvr$getCombatLoadoutMain();
            ItemStack loadoutOff = acc.ezvr$getCombatLoadoutOff();
            if ((loadoutMain == null || loadoutMain.isEmpty()) && (loadoutOff == null || loadoutOff.isEmpty())) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getItemRenderer() == null) return;

            ModelPart body = resolveBodyPart(getParentModel());

            if (ENABLE_WAIST && loadoutMain != null && !loadoutMain.isEmpty()) {
                renderOne(
                        villager,
                        loadoutMain,
                        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                        false,
                        body,
                        poseStack,
                        buffer,
                        packedLight,
                        WAIST_TX, WAIST_TY, WAIST_TZ,
                        WAIST_RX_DEG, WAIST_RY_DEG, WAIST_RZ_DEG,
                        WAIST_SCALE
                );
            }

            if (ENABLE_BACK && loadoutOff != null && !loadoutOff.isEmpty()) {
                renderOne(
                        villager,
                        loadoutOff,
                        ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                        true,
                        body,
                        poseStack,
                        buffer,
                        packedLight,
                        BACK_TX, BACK_TY, BACK_TZ,
                        BACK_RX_DEG, BACK_RY_DEG, BACK_RZ_DEG,
                        BACK_SCALE
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHolsteredLoadoutLayer.render failed (soft): {}", t.toString());
        }
    }

    private static void renderOne(Villager villager,
                                  ItemStack stack,
                                  ItemDisplayContext ctx,
                                  boolean leftHand,
                                  ModelPart bodyOrNull,
                                  PoseStack poseStack,
                                  MultiBufferSource buffer,
                                  int packedLight,
                                  float tx, float ty, float tz,
                                  float rxDeg, float ryDeg, float rzDeg,
                                  float scale) {
        try {
            if (stack == null || stack.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getItemRenderer() == null) return;

            poseStack.pushPose();
            try {
                if (bodyOrNull != null) {
                    bodyOrNull.translateAndRotate(poseStack);
                }

                poseStack.translate(tx, ty, tz);
                if (rxDeg != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(rxDeg));
                if (ryDeg != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(ryDeg));
                if (rzDeg != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(rzDeg));
                if (scale != 1.0f) poseStack.scale(scale, scale, scale);

                mc.getItemRenderer().renderStatic(
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

        } catch (Throwable ignored) {}
    }

    private static ModelPart resolveBodyPart(VillagerModel<?> model) {
        try {
            if (model == null) return null;
            ModelPart root = tryCallRoot(model);
            if (root == null) return null;

            try {
                return root.getChild("body");
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ModelPart tryCallRoot(VillagerModel<?> model) {
        try {
            if (model == null) return null;

            Method m = ROOT_METHOD;
            if (m == null) {
                m = model.getClass().getMethod("root");
                m.setAccessible(true);
                ROOT_METHOD = m;
            }

            Object v = m.invoke(model);
            return (v instanceof ModelPart mp) ? mp : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
