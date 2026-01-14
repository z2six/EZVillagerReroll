// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHumanoidArmsLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.client.model.VillagerCombatArmsModel;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Method;

/**
 * Renders custom player-like left/right arms on a villager and animates them using vanilla HumanoidModel code.
 *
 * IMPORTANT CHANGE:
 * - This layer no longer decides WHEN arms should render.
 * - VillagerBrain is the sole authority: it writes synced flags to the villager each tick.
 * - This layer only renders when those flags say "render custom arms".
 */
public final class VillagerHumanoidArmsLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    // =========================================================================================
    // TWEAKS
    // =========================================================================================

    /** Easy alignment knobs (start tiny: 0.001..0.02). */
    private static final boolean ENABLE_ARMS_TRANSFORM = true;
    private static final float ARMS_TX = 0.0f;
    private static final float ARMS_TY = 0.0f;
    private static final float ARMS_TZ = 0.0f;
    private static final float ARMS_SCALE = 1.0f;

    /** Custom texture for the added arms. */
    private static final ResourceLocation ARMS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/villager/villager_arms.png");

    /** Color tint for the arms texture (ARGB). */
    private static final int PACKED_COLOR = 0xFFFFFFFF;

    // =========================================================================================

    private final VillagerCombatArmsModel armsModel;
    private final HumanoidModel<LivingEntity> driverHumanoid;

    public VillagerHumanoidArmsLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                     VillagerCombatArmsModel armsModel,
                                     HumanoidModel<LivingEntity> driverHumanoid) {
        super(parent);
        this.armsModel = armsModel;
        this.driverHumanoid = driverHumanoid;
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
            if (villager == null || armsModel == null || driverHumanoid == null) return;

            // Brain-authoritative gate: default is "do not render custom arms".
            if (!shouldRenderCustomArms(villager)) {
                return;
            }

            // 1) Drive the vanilla humanoid animation state from villager
            setupDriverState(villager, partialTick);

            // 2) Run vanilla humanoid animation code (sets rightArm/leftArm rotations)
            driverHumanoid.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            // 3) Copy rotations into our custom arms model parts
            armsModel.setArmRotationsFromHumanoid(driverHumanoid.rightArm, driverHumanoid.leftArm);

            // 4) Render arms with custom texture
            poseStack.pushPose();

            // Redundant safety: hide vanilla crossed-arms part while we draw (brain also handles via model mixin)
            ModelPart vanillaArms = null;
            boolean prevVisible = true;
            try {
                vanillaArms = tryResolveVanillaArmsPart(getParentModel());
                if (vanillaArms != null) {
                    prevVisible = vanillaArms.visible;
                    vanillaArms.visible = false;
                }

                if (ENABLE_ARMS_TRANSFORM) {
                    poseStack.translate(ARMS_TX, ARMS_TY, ARMS_TZ);
                    poseStack.scale(ARMS_SCALE, ARMS_SCALE, ARMS_SCALE);
                }

                var vc = buffer.getBuffer(RenderType.entityCutoutNoCull(ARMS_TEXTURE));
                armsModel.renderArms(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, PACKED_COLOR);

            } finally {
                if (vanillaArms != null) {
                    try { vanillaArms.visible = prevVisible; } catch (Throwable ignored) {}
                }
                poseStack.popPose();
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerHumanoidArmsLayer.render failed (soft): {}", t.toString());
        }
    }

    // =========================================================================================
    // Brain-synced decision
    // =========================================================================================

    private static boolean shouldRenderCustomArms(Villager v) {
        try {
            if (v instanceof VillagerOverhaulRenderAccess acc) {
                return VillagerRenderFlags.renderCustomArms(acc.ezvr$getRenderFlags());
            }
        } catch (Throwable ignored) {}
        return false; // default: do not render custom arms
    }

    // =========================================================================================
    // Vanilla humanoid pose driving (ArmPose + attackTime etc)
    // =========================================================================================

    private void setupDriverState(Villager v, float partialTick) {
        try {
            driverHumanoid.attackTime = v.getAttackAnim(partialTick);
            driverHumanoid.riding = v.isPassenger();
            driverHumanoid.young = v.isBaby();

            driverHumanoid.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            driverHumanoid.rightArmPose = HumanoidModel.ArmPose.EMPTY;

            if (v.isUsingItem()) {
                InteractionHand hand = v.getUsedItemHand();
                ItemStack using = v.getUseItem();

                HumanoidArm mainArm = v.getMainArm();
                boolean usingMainHand = (hand == InteractionHand.MAIN_HAND);

                boolean activeIsRight = usingMainHand
                        ? (mainArm == HumanoidArm.RIGHT)
                        : (mainArm != HumanoidArm.RIGHT);

                HumanoidModel.ArmPose pose = armPoseFor(using);

                if (activeIsRight) driverHumanoid.rightArmPose = pose;
                else driverHumanoid.leftArmPose = pose;
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] setupDriverState failed (soft): {}", t.toString());
        }
    }

    private static HumanoidModel.ArmPose armPoseFor(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return HumanoidModel.ArmPose.EMPTY;

            if (stack.is(Items.SHIELD)) return HumanoidModel.ArmPose.BLOCK;

            UseAnim anim = stack.getUseAnimation();
            if (anim == UseAnim.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (anim == UseAnim.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (anim == UseAnim.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;

            return HumanoidModel.ArmPose.ITEM;

        } catch (Throwable ignored) {
            return HumanoidModel.ArmPose.ITEM;
        }
    }

    // =========================================================================================
    // Redundant safety: resolve vanilla crossed-arms bone on VillagerModel
    // =========================================================================================

    private static ModelPart tryResolveVanillaArmsPart(VillagerModel<?> model) {
        try {
            if (model == null) return null;

            ModelPart root = tryCallRoot(model);
            if (root != null) {
                ModelPart p = tryChild(root, "arms");
                if (p != null) return p;
                p = tryChild(root, "crossed_arms");
                if (p != null) return p;
                p = tryChild(root, "crossedArms");
                if (p != null) return p;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ModelPart tryCallRoot(Object model) {
        try {
            Method m = model.getClass().getMethod("root");
            Object out = m.invoke(model);
            if (out instanceof ModelPart mp) return mp;
        } catch (Throwable ignored) {}
        return null;
    }

    private static ModelPart tryChild(ModelPart root, String name) {
        try {
            if (root == null || name == null) return null;
            return root.getChild(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}