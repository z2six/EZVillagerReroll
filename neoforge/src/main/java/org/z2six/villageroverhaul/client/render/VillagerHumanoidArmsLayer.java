// VillagerHumanoidArmsLayer.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHumanoidArmsLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
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
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.client.model.VillagerCombatArmsModel;

/**
 * Renders "normal" left/right arms on a villager and animates them using vanilla HumanoidModel animation code.
 *
 * Notes:
 * - This layer ONLY draws the extra arms. It does NOT hide vanilla crossed arms.
 * - If you want to verify rendering quickly, set ONLY_RENDER_WHEN_COMBATISH = false.
 * - The arms use a custom texture (ARMS_TEXTURE). Put it under:
 *   resources/assets/<modid>/textures/entity/villager_arms.png
 */
public final class VillagerHumanoidArmsLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    // =========================================================================================
    // CONFIG / TWEAKS
    // =========================================================================================

    /** If true, arms only render when villager is swinging/using item/holding "weaponish" items. */
    private static final boolean ONLY_RENDER_WHEN_COMBATISH = false;

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

            if (ONLY_RENDER_WHEN_COMBATISH && !isCombatish(villager, partialTick)) {
                return;
            }

            // 1) Drive the vanilla humanoid animation state from villager
            setupDriverState(villager, partialTick);

            // 2) Run vanilla humanoid animation code (this sets rightArm/leftArm rotations)
            driverHumanoid.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            // 3) Copy rotations into our custom arms model parts
            armsModel.setArmRotationsFromHumanoid(driverHumanoid.rightArm, driverHumanoid.leftArm);

            // 4) Render arms with custom texture
            poseStack.pushPose();
            try {
                if (ENABLE_ARMS_TRANSFORM) {
                    poseStack.translate(ARMS_TX, ARMS_TY, ARMS_TZ);
                    poseStack.scale(ARMS_SCALE, ARMS_SCALE, ARMS_SCALE);
                }

                var vc = buffer.getBuffer(RenderType.entityCutoutNoCull(ARMS_TEXTURE));
                armsModel.renderArms(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, PACKED_COLOR);

            } finally {
                poseStack.popPose();
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerHumanoidArmsLayer.render failed (soft): {}", t.toString());
        }
    }

    // =========================================================================================
    // Combat-ish detection
    // =========================================================================================

    private static boolean isCombatish(Villager v, float partialTick) {
        try {
            if (v.getAttackAnim(partialTick) > 0.0f) return true;
            if (v.isUsingItem()) return true;

            ItemStack main = v.getMainHandItem();
            ItemStack off = v.getOffhandItem();

            return isWeaponish(main) || isWeaponish(off);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWeaponish(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return false;

            // crude but effective for visuals
            return s.is(Items.SHIELD)
                    || s.getItem() instanceof net.minecraft.world.item.SwordItem
                    || s.getItem() instanceof net.minecraft.world.item.AxeItem
                    || s.getItem() instanceof net.minecraft.world.item.BowItem
                    || s.getItem() instanceof CrossbowItem
                    || s.getItem() instanceof net.minecraft.world.item.TridentItem;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =========================================================================================
    // Vanilla humanoid pose driving (ArmPose + attackTime etc)
    // =========================================================================================

    private void setupDriverState(Villager v, float partialTick) {
        try {
            // These fields are used by HumanoidModel#setupAnim
            driverHumanoid.attackTime = v.getAttackAnim(partialTick);
            driverHumanoid.riding = v.isPassenger();
            driverHumanoid.young = v.isBaby();

            // Reset poses each frame
            driverHumanoid.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            driverHumanoid.rightArmPose = HumanoidModel.ArmPose.EMPTY;

            if (v.isUsingItem()) {
                InteractionHand hand = v.getUsedItemHand();
                ItemStack using = v.getUseItem();

                HumanoidArm mainArm = v.getMainArm();
                boolean usingMainHand = (hand == InteractionHand.MAIN_HAND);

                // Determine which side is "active" for using item
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

            // Shield / blocking
            if (stack.is(Items.SHIELD)) return HumanoidModel.ArmPose.BLOCK;

            UseAnim anim = stack.getUseAnimation();
            if (anim == UseAnim.BLOCK) return HumanoidModel.ArmPose.BLOCK;
            if (anim == UseAnim.BOW) return HumanoidModel.ArmPose.BOW_AND_ARROW;
            if (anim == UseAnim.SPYGLASS) return HumanoidModel.ArmPose.SPYGLASS;

            // Reasonable default for EAT/DRINK/TOOT_HORN/etc.
            return HumanoidModel.ArmPose.ITEM;

        } catch (Throwable ignored) {
            return HumanoidModel.ArmPose.ITEM;
        }
    }
}