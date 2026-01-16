// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerCombatArmsModel.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;

/**
 * Geometry-only model containing the villager's "normal" left/right arms.
 * Animation is driven externally by VillagerHumanoidArmsLayer via a HumanoidModel driver.
 *
 * ALSO provides helper transforms so held items can be rendered anchored to these arms.
 */
public final class VillagerCombatArmsModel extends EntityModel<Villager> {

    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "villager_combat_arms"), "main");

    private final ModelPart leftArm;
    private final ModelPart rightArm;

    // =========================================================================================
    // HELD-ITEM TWEAKS (model-space)
    // These emulate vanilla "held item in hand" offsets.
    // If items look slightly off, tweak these first.
    // =========================================================================================

    /** Small side offset so the item sits in the hand instead of centered. */
    private static final float HAND_SIDE_NUDGE = 1.0f / 16.0f; // 0.0625

    /** How far down the arm to the hand pivot. */
    private static final float HAND_Y = 10.0f / 16.0f; // 0.625

    /** Forward/back nudge for hand pivot. */
    private static final float HAND_Z = 0.0f;

    public VillagerCombatArmsModel(ModelPart root) {
        this.leftArm = safeGetChild(root, "left_arm");
        this.rightArm = safeGetChild(root, "right_arm");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition root = meshdefinition.getRoot();

        root.addOrReplaceChild("left_arm",
                CubeListBuilder.create()
                        .texOffs(44, 22)
                        .mirror()
                        .addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .mirror(false),
                PartPose.offset(5.0F, 2.0F, 0.0F));

        root.addOrReplaceChild("right_arm",
                CubeListBuilder.create()
                        .texOffs(44, 22)
                        .addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-5.0F, 2.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(Villager entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        // Intentionally empty: animation is driven externally.
    }

    public void setArmRotationsFromHumanoid(ModelPart humanoidRightArm, ModelPart humanoidLeftArm) {
        try {
            if (humanoidRightArm != null && this.rightArm != null) {
                this.rightArm.xRot = humanoidRightArm.xRot;
                this.rightArm.yRot = humanoidRightArm.yRot;
                this.rightArm.zRot = humanoidRightArm.zRot;
            }
            if (humanoidLeftArm != null && this.leftArm != null) {
                this.leftArm.xRot = humanoidLeftArm.xRot;
                this.leftArm.yRot = humanoidLeftArm.yRot;
                this.leftArm.zRot = humanoidLeftArm.zRot;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatArmsModel.setArmRotationsFromHumanoid failed (soft): {}", t.toString());
        }
    }

    public void renderArms(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay, int packedColor) {
        try {
            if (this.rightArm != null) this.rightArm.render(poseStack, consumer, packedLight, packedOverlay, packedColor);
            if (this.leftArm != null) this.leftArm.render(poseStack, consumer, packedLight, packedOverlay, packedColor);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatArmsModel.renderArms failed (soft): {}", t.toString());
        }
    }

    /**
     * Move the PoseStack to the hand pivot of the specified arm.
     * This is used by held-item rendering layers.
     */
    public void translateToHand(HumanoidArm arm, PoseStack ps) {
        try {
            if (ps == null || arm == null) return;

            ModelPart part = (arm == HumanoidArm.RIGHT) ? rightArm : leftArm;
            if (part == null) return;

            // Shoulder + arm rotation
            part.translateAndRotate(ps);

            // Down to hand + slight side nudge
            float side = (arm == HumanoidArm.RIGHT) ? -HAND_SIDE_NUDGE : HAND_SIDE_NUDGE;
            ps.translate(side, HAND_Y, HAND_Z);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] translateToHand failed (soft): {}", t.toString());
        }
    }

    // IMPORTANT: your mappings expect this 5-arg version
    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int packedColor) {
        renderArms(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor);
    }

    private static ModelPart safeGetChild(ModelPart root, String name) {
        try {
            if (root == null) return null;
            return root.getChild(name);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] Missing ModelPart child '{}' in VillagerCombatArmsModel layer.", name);
            return null;
        }
    }
}
