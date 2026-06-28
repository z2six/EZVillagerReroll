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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.VillagerGenderService;

public final class DwarfVillagerModel extends EntityModel<Villager> {
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "dwarf_villager"), "main");

    private final ModelPart root;
    private final ModelPart belly;
    private final ModelPart breasts;
    private final ModelPart arms;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;
    private final ModelPart head;

    private static final float HAND_SIDE_NUDGE = 1.0f / 16.0f;
    private static final float HAND_Y = 12.0f / 16.0f;
    private static final float HAND_Z = 0.0f;

    public DwarfVillagerModel(ModelPart root) {
        ModelPart contentRoot = child(root, "root");
        this.root = contentRoot == null ? root : contentRoot;
        ModelPart body = child(this.root, "body");
        this.belly = child(body, "belly");
        this.breasts = child(body, "breasts");
        this.arms = child(this.root, "arms");
        this.leftArm = child(this.arms, "left_arm");
        this.rightArm = child(this.arms, "right_arm");
        this.leftLeg = child(this.root, "left_leg");
        this.rightLeg = child(this.root, "right_leg");
        this.head = child(this.root, "head");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition modelRoot = root.addOrReplaceChild("root",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition body = modelRoot.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 30)
                        .addBox(-6.5F, 0.0F, -4.0F, 13.0F, 16.0F, 11.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -22.0F, 0.0F));

        body.addOrReplaceChild("belly",
                CubeListBuilder.create().texOffs(72, 38)
                        .addBox(-6.5F, -4.5F, -2.0F, 13.0F, 9.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 11.5F, -4.0F));

        PartDefinition breasts = body.addOrReplaceChild("breasts",
                CubeListBuilder.create(),
                PartPose.offset(0.5F, 0.0F, 0.0F));

        breasts.addOrReplaceChild("left_breast_cube_r1",
                CubeListBuilder.create().texOffs(86, 24)
                        .addBox(0.5F, -1.5F, -2.5F, 6.0F, 5.0F, 5.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-0.25F, 5.5F, -4.25F, 0.2182F, 0.0F, 0.0F));

        breasts.addOrReplaceChild("right_breast_cube_r1",
                CubeListBuilder.create().texOffs(86, 13)
                        .addBox(-5.5F, -1.5F, -2.5F, 6.0F, 5.0F, 5.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(-0.75F, 5.5F, -4.25F, 0.2182F, 0.0F, 0.0F));

        body.addOrReplaceChild("dwarf_jacket",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-7.0F, 0.0F, -4.0F, 13.0F, 18.0F, 11.0F, new CubeDeformation(0.25F)),
                PartPose.offset(0.5F, 0.0F, 0.0F));

        PartDefinition arms = modelRoot.addOrReplaceChild("arms",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, -19.0F, 1.0F));

        arms.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(0, 58)
                        .addBox(-1.0F, -2.0F, -3.0F, 5.0F, 15.0F, 6.0F, new CubeDeformation(0.0F))
                        .texOffs(49, 38)
                        .addBox(-1.0F, -2.0F, -3.0F, 5.0F, 15.0F, 6.0F, new CubeDeformation(-0.25F)),
                PartPose.offset(6.5F, 0.0F, 0.0F));

        arms.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(23, 58)
                        .addBox(-4.0F, -2.0F, -3.0F, 5.0F, 15.0F, 6.0F, new CubeDeformation(0.0F))
                        .texOffs(46, 60)
                        .addBox(-4.0F, -2.0F, -3.0F, 5.0F, 15.0F, 6.0F, new CubeDeformation(-0.25F)),
                PartPose.offset(-6.5F, 0.0F, 0.0F));

        modelRoot.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(23, 80)
                        .addBox(-2.5F, 0.0F, -2.5F, 5.0F, 7.0F, 5.0F, new CubeDeformation(0.25F))
                        .texOffs(69, 74)
                        .addBox(-2.5F, 0.0F, -2.5F, 5.0F, 7.0F, 5.0F, new CubeDeformation(0.0F)),
                PartPose.offset(3.0F, -7.0F, 0.5F));

        modelRoot.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(44, 82)
                        .addBox(-2.5F, 0.0F, -2.5F, 5.0F, 7.0F, 5.0F, new CubeDeformation(0.25F))
                        .texOffs(86, 0)
                        .addBox(-2.5F, 0.0F, -2.5F, 5.0F, 7.0F, 5.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-3.0F, -7.0F, 0.5F));

        PartDefinition head = modelRoot.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(49, 0)
                        .addBox(-4.5F, -5.0F, -7.0F, 9.0F, 9.0F, 9.0F, new CubeDeformation(0.25F))
                        .texOffs(49, 19)
                        .addBox(-4.5F, -5.0F, -7.0F, 9.0F, 9.0F, 9.0F, new CubeDeformation(0.5F))
                        .texOffs(72, 50)
                        .addBox(-5.5F, -4.0F, -8.25F, 11.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -22.0F, 0.0F));

        head.addOrReplaceChild("head_cube_ear_right_r1",
                CubeListBuilder.create().texOffs(65, 87).mirror()
                        .addBox(-5.3516F, -6.25F, -3.6176F, 0.0F, 4.0F, 5.0F, new CubeDeformation(0.0F))
                        .mirror(false),
                PartPose.offsetAndRotation(-0.5F, 4.0F, 0.0F, 0.0F, -0.2618F, 0.0F));

        head.addOrReplaceChild("head_cube_ear_left_r1",
                CubeListBuilder.create().texOffs(65, 87)
                        .addBox(5.3516F, -6.25F, -3.6176F, 0.0F, 4.0F, 5.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.5F, 4.0F, 0.0F, 0.0F, 0.2618F, 0.0F));

        PartDefinition nose = head.addOrReplaceChild("nose",
                CubeListBuilder.create(),
                PartPose.offset(0.0F, -1.0F, -7.5F));

        nose.addOrReplaceChild("nose_cube_r1",
                CubeListBuilder.create().texOffs(89, 87)
                        .addBox(-1.5F, -0.9791F, -1.4559F, 3.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.0436F, 0.0F, 0.0F));

        head.addOrReplaceChild("beard",
                CubeListBuilder.create().texOffs(0, 80)
                        .addBox(-3.5F, -1.0F, -1.0F, 7.0F, 6.0F, 4.0F, new CubeDeformation(0.25F))
                        .texOffs(76, 87)
                        .addBox(-1.5F, 5.0F, -0.75F, 3.0F, 3.0F, 3.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 2.0F, -7.0F));

        PartDefinition hat = head.addOrReplaceChild("dwarf_hat",
                CubeListBuilder.create().texOffs(92, 67)
                        .addBox(-4.5F, -7.0F, -7.0F, 9.0F, 3.0F, 9.0F, new CubeDeformation(0.51F)),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        hat.addOrReplaceChild("dwarf_hat_rim",
                CubeListBuilder.create().texOffs(64, 97)
                        .addBox(-8.0F, -4.75F, -10.5F, 16.0F, 1.0F, 16.0F, new CubeDeformation(0.25F)),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void setupAnim(Villager villager, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        try {
            if (head != null) {
                head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
                head.xRot = headPitch * Mth.DEG_TO_RAD;
                head.zRot = 0.0F;
                if (villager != null && villager.getUnhappyCounter() > 0) {
                    head.zRot = 0.3F * Mth.sin(0.45F * ageInTicks);
                    head.xRot = 0.4F;
                }
            }
            if (rightLeg != null) {
                rightLeg.xRot = Mth.cos(limbSwing * 0.6662F) * 1.4F * limbSwingAmount * 0.5F;
                rightLeg.yRot = 0.0F;
            }
            if (leftLeg != null) {
                leftLeg.xRot = Mth.cos(limbSwing * 0.6662F + Mth.PI) * 1.4F * limbSwingAmount * 0.5F;
                leftLeg.yRot = 0.0F;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] DwarfVillagerModel.setupAnim failed (soft): {}", t.toString());
        }
    }

    public void renderBase(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay, int packedColor, boolean renderCrossedArms, int genderId, boolean hideBodyShape) {
        boolean previousBellyVisible = belly == null || belly.visible;
        boolean previousBreastsVisible = breasts == null || breasts.visible;
        try {
            applyGenderVisibility(genderId, hideBodyShape);
            if (arms != null) arms.visible = renderCrossedArms;
            root.render(poseStack, consumer, packedLight, packedOverlay, packedColor);
        } finally {
            if (arms != null) arms.visible = true;
            if (belly != null) belly.visible = previousBellyVisible;
            if (breasts != null) breasts.visible = previousBreastsVisible;
        }
    }

    public void renderOverlay(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay, int packedColor, boolean renderCrossedArms, int genderId, boolean hideBodyShape) {
        renderBase(poseStack, consumer, packedLight, packedOverlay, packedColor, renderCrossedArms, genderId, hideBodyShape);
    }

    public void setArmRotationsFromHumanoid(ModelPart humanoidRightArm, ModelPart humanoidLeftArm) {
        try {
            if (humanoidRightArm != null && rightArm != null) {
                rightArm.xRot = humanoidRightArm.xRot;
                rightArm.yRot = humanoidRightArm.yRot;
                rightArm.zRot = humanoidRightArm.zRot;
            }
            if (humanoidLeftArm != null && leftArm != null) {
                leftArm.xRot = humanoidLeftArm.xRot;
                leftArm.yRot = humanoidLeftArm.yRot;
                leftArm.zRot = humanoidLeftArm.zRot;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] DwarfVillagerModel.setArmRotationsFromHumanoid failed (soft): {}", t.toString());
        }
    }

    public void translateToHand(HumanoidArm arm, PoseStack poseStack) {
        try {
            if (arm == null || poseStack == null || root == null || arms == null) return;

            ModelPart part = arm == HumanoidArm.RIGHT ? rightArm : leftArm;
            if (part == null) return;

            root.translateAndRotate(poseStack);
            arms.translateAndRotate(poseStack);
            part.translateAndRotate(poseStack);

            float side = arm == HumanoidArm.RIGHT ? -HAND_SIDE_NUDGE : HAND_SIDE_NUDGE;
            poseStack.translate(side, HAND_Y, HAND_Z);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] DwarfVillagerModel.translateToHand failed (soft): {}", t.toString());
        }
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int packedColor) {
        renderBase(poseStack, vertexConsumer, packedLight, packedOverlay, packedColor, true, VillagerGenderService.GENDER_UNKNOWN, false);
    }

    private void applyGenderVisibility(int genderId, boolean hideBodyShape) {
        if (hideBodyShape) {
            if (breasts != null) breasts.visible = false;
            if (belly != null) belly.visible = false;
            return;
        }
        if (breasts != null) breasts.visible = genderId != VillagerGenderService.GENDER_MALE;
        if (belly != null) belly.visible = genderId != VillagerGenderService.GENDER_FEMALE;
    }

    private static ModelPart child(ModelPart parent, String name) {
        try {
            return parent == null ? null : parent.getChild(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
