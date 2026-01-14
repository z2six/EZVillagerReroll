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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

public final class VillagerHumanoidArmsLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    // =========================================================================================
    // TWEAKS
    // =========================================================================================

    private static final boolean ENABLE_ARMS_TRANSFORM = true;
    private static final float ARMS_TX = 0.0f;
    private static final float ARMS_TY = 0.0f;
    private static final float ARMS_TZ = 0.0f;
    private static final float ARMS_SCALE = 1.0f;

    private static final ResourceLocation ARMS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/villager/villager_arms.png");

    private static final int PACKED_COLOR = 0xFFFFFFFF;

    // =========================================================================================

    private final VillagerCombatArmsModel armsModel;
    private final HumanoidModel<LivingEntity> driverHumanoid;

    private boolean ezvr$initLogged = false;

    // Only used for diagnostics (not for hiding anymore)
    private boolean ezvr$resolvedVanillaCrossed = false;
    private String ezvr$vanillaCrossedPath = null;

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

            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VillagerHumanoidArmsLayer ACTIVE");
            }

            if (!shouldRenderCustomArms(villager)) {
                return;
            }

            // Diagnostic resolution once: tells us what the base model calls crossed arms in the baked tree
            ezvr$resolveVanillaCrossedOnce();

            // 1) Drive humanoid animation state
            setupDriverState(villager, partialTick);

            // 2) Run vanilla humanoid anim
            driverHumanoid.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            // 3) Copy rotations to our arms
            armsModel.setArmRotationsFromHumanoid(driverHumanoid.rightArm, driverHumanoid.leftArm);

            // 4) Render custom arms
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
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VillagerHumanoidArmsLayer.render failed (soft): {}", t.toString());
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
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] setupDriverState failed (soft): {}", t.toString());
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

    // -------------------------------------------------------------------------
    // Diagnostic: resolve crossed arms name/path in baked runtime tree
    // -------------------------------------------------------------------------

    private void ezvr$resolveVanillaCrossedOnce() {
        if (ezvr$resolvedVanillaCrossed) return;
        ezvr$resolvedVanillaCrossed = true;

        try {
            VillagerModel<?> model = getParentModel();
            ModelPart root = tryCallRoot(model);

            if (root == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] ArmsLayer: failed to get parentModel.root()");
                return;
            }

            String[] armsPathOut = new String[1];
            ModelPart arms = findFirstByName(root, "arms", armsPathOut);

            if (arms != null) {
                ezvr$vanillaCrossedPath = armsPathOut[0];
            } else {
                String[] bonePathOut = new String[1];
                ModelPart bone = findFirstByName(root, "bone", bonePathOut);
                ezvr$vanillaCrossedPath = bonePathOut[0];
            }

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] [client] ArmsLayer resolved vanilla crossed path='{}'",
                    String.valueOf(ezvr$vanillaCrossedPath)
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] ArmsLayer resolveVanillaCrossedOnce failed (soft): {}", t.toString());
        }
    }

    private static ModelPart tryCallRoot(Object model) {
        try {
            if (model == null) return null;
            Method m = model.getClass().getMethod("root");
            Object out = m.invoke(model);
            return (out instanceof ModelPart mp) ? mp : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static ModelPart findFirstByName(ModelPart root, String wanted, String[] outPath) {
        try {
            if (outPath != null && outPath.length > 0) outPath[0] = null;
            if (root == null || wanted == null || wanted.isBlank()) return null;

            IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
            return findRec(root, wanted, "root", visited, 0, outPath);

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ModelPart findRec(ModelPart node,
                                     String wanted,
                                     String path,
                                     IdentityHashMap<ModelPart, Boolean> visited,
                                     int depth,
                                     String[] outPath) {
        try {
            if (node == null) return null;
            if (visited.put(node, Boolean.TRUE) != null) return null;
            if (depth > 64) return null;

            Map<String, ModelPart> children = getChildrenMap(node);
            if (children == null || children.isEmpty()) return null;

            ModelPart direct = children.get(wanted);
            if (direct != null) {
                if (outPath != null && outPath.length > 0) outPath[0] = path + "." + wanted;
                return direct;
            }

            for (Map.Entry<String, ModelPart> e : children.entrySet()) {
                String k = e.getKey();
                ModelPart v = e.getValue();
                if (k == null || v == null) continue;

                ModelPart found = findRec(v, wanted, path + "." + k, visited, depth + 1, outPath);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> getChildrenMap(ModelPart part) {
        try {
            for (Field f : ModelPart.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(part);
                if (!(v instanceof Map<?, ?> m)) continue;

                if (!m.isEmpty()) {
                    Object anyKey = m.keySet().iterator().next();
                    Object anyVal = m.values().iterator().next();
                    if (anyKey instanceof String && anyVal instanceof ModelPart) {
                        return (Map<String, ModelPart>) m;
                    }
                } else {
                    return (Map<String, ModelPart>) m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
