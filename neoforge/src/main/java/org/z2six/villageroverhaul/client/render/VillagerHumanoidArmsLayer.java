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
import net.minecraft.util.Mth;
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
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VillagerHumanoidArmsLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    private static final boolean ENABLE_ARMS_TRANSFORM = true;
    private static final float ARMS_TX = 0.0f;
    private static final float ARMS_TY = 0.0f;
    private static final float ARMS_TZ = 0.0f;
    private static final float ARMS_SCALE = 1.0f;

    private static final ResourceLocation ARMS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/villager/villager_arms.png");

    private static final int PACKED_COLOR = 0xFFFFFFFF;

    // Local animation window for a single swing (you set this to 4 for speed)
    private static final int LOCAL_SWING_DURATION_TICKS = 6;

    // Manual swing tuning
    private static final float SWING_XROT_SCALE = 1.20f;
    private static final float SWING_ZROT_SCALE = 0.40f;

    /**
     * Requirement #1: start ~20% lower without changing end pose.
     * We tighten the swing cone by scaling amplitude down ~20%.
     */
    private static final float SWING_CONE_SCALE = 0.80f; // 20% lower cone

    // Manual eat pose tuning (for UseAnim.EAT)
    // Increased inward/upward rotation to bring the hand closer to the mouth.
    private static final float EAT_XROT_BASE = -1.55f;
    private static final float EAT_XROT_WOBBLE = 0.10f;
    private static final float EAT_YROT_IN = 0.60f;
    private static final float EAT_ZROT_TWIST = 0.16f;
    private static final float EAT_WOBBLE_SPEED = 0.70f;

    /**
     * Additional improvement: avoid the first frame snapping "too high" when duration is short (4 ticks).
     * We ease-in the progress before taking sin(), while still preserving endpoints.
     */
    private static final boolean ENABLE_EASED_PROGRESS = true;

    // INFO throttle
    private static final int INFO_LOG_INTERVAL_TICKS = 40;
    private static final Map<UUID, Integer> LAST_INFO_TICK = new HashMap<>();

    // Per-entity swing tracking (weak so entities can GC)
    private static final Map<Integer, SwingTrack> SWING_TRACK = new HashMap<>();

    private static final class SwingTrack {
        int lastSeq = 0;
        long localStartGameTime = -1L;
        WeakReference<Villager> ref;

        SwingTrack(Villager v) {
            this.ref = new WeakReference<>(v);
        }
    }

    private final VillagerCombatArmsModel armsModel;
    private final HumanoidModel<LivingEntity> driverHumanoid;

    private boolean ezvr$initLogged = false;

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
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VillagerHumanoidArmsLayer ACTIVE (seq-driven swing)");
            }

            if (!shouldRenderCustomArms(villager)) return;

            float swingProg = computeSeqDrivenSwingProgress(villager, partialTick);

            setupDriverState(villager, swingProg);

            driverHumanoid.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            applyManualEatPoseToDriverArms(villager, partialTick, ageInTicks);

            applyManualSwingToDriverArms(villager, swingProg);

            armsModel.setArmRotationsFromHumanoid(driverHumanoid.rightArm, driverHumanoid.leftArm);

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

            logClientSwingProof(villager, swingProg);

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

    private static float computeSeqDrivenSwingProgress(Villager v, float partialTick) {
        try {
            if (v == null || v.level() == null) return 0.0f;

            int id = v.getId();
            SwingTrack tr = SWING_TRACK.get(id);
            if (tr == null || tr.ref == null || tr.ref.get() != v) {
                tr = new SwingTrack(v);
                SWING_TRACK.put(id, tr);
            }

            int seq = 0;
            if (v instanceof VillagerOverhaulSwingAccess acc) {
                seq = acc.ezvr$getSwingSeq();
            }

            long now = v.level().getGameTime();

            // Requirement #2: new attack -> cancel current swing and restart.
            if (seq != tr.lastSeq) {
                int prev = tr.lastSeq;
                tr.lastSeq = seq;
                tr.localStartGameTime = now;

                // Debug only; prints exactly when we restart due to a new attack swing.
                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] [client] swingSeq changed (villager={} {}->{}). Restarting local swing anim.",
                        v.getUUID(), prev, seq
                );
            }

            if (tr.localStartGameTime < 0L) return 0.0f;

            long dt = now - tr.localStartGameTime;
            if (dt < 0L) dt = 0L;

            if (dt >= LOCAL_SWING_DURATION_TICKS) return 0.0f;

            float p = (dt + partialTick) / (float) LOCAL_SWING_DURATION_TICKS;
            if (p < 0.0f) p = 0.0f;
            if (p > 1.0f) p = 1.0f;
            return p;

        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private void setupDriverState(Villager v, float swingProg) {
        try {
            driverHumanoid.attackTime = swingProg;
            driverHumanoid.riding = v.isPassenger();
            driverHumanoid.young = v.isBaby();

            driverHumanoid.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            driverHumanoid.rightArmPose = HumanoidModel.ArmPose.EMPTY;

            // If the server forced an eat window (FLAG_EATING_POSE), but client-side "using item"
            // didn't sync, start using the mainhand item locally so vanilla ITEM-use animation can run.
            // (Particles are server-driven via entity event 9.)
            if (!v.isUsingItem() && isEatingPoseForced(v)) {
                ItemStack mh = v.getMainHandItem();
                if (mh != null && !mh.isEmpty() && mh.getUseAnimation() == UseAnim.EAT) {
                    try { v.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
                }
            }

            // If using item (eat/bow/block), do not swing.
            if (v.isUsingItem() || isEatingPoseForced(v)) {
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

                // Suppress swing if using item.
                driverHumanoid.attackTime = 0.0f;
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] setupDriverState failed (soft): {}", t.toString());
        }
    }

    private void applyManualSwingToDriverArms(Villager v, float p) {
        try {
            if (v == null || driverHumanoid == null) return;
            if (v.isUsingItem()) return;
            if (isEatingPoseForced(v)) return;
            if (p <= 0.0001f) return;

            // Optionally ease-in/out to avoid the first frame "jump" when duration is very short.
            float pe = p;
            if (ENABLE_EASED_PROGRESS) {
                pe = smoothStep01(p);
            }

            HumanoidArm armToSwing = v.getMainArm();
            ModelPart arm = (armToSwing == HumanoidArm.RIGHT) ? driverHumanoid.rightArm : driverHumanoid.leftArm;
            if (arm == null) return;

            // Vanilla-like swing curve using sin(), but with tighter cone and eased start.
            float f1 = Mth.sin(pe * (float) Math.PI);

            float eased = 1.0F - (1.0F - pe) * (1.0F - pe);
            float f2 = Mth.sin(eased * (float) Math.PI);

            float cone = SWING_CONE_SCALE; // 0.80 => ~20% lower arc

            arm.xRot -= (f1 * (SWING_XROT_SCALE * cone) + f2 * (0.4f * cone));

            float twist = Mth.sin(pe * (float) Math.PI) * (SWING_ZROT_SCALE * cone);
            if (armToSwing == HumanoidArm.RIGHT) arm.zRot -= twist;
            else arm.zRot += twist;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] applyManualSwingToDriverArms failed (soft): {}", t.toString());
        }
    }

    private void applyManualEatPoseToDriverArms(Villager v, float partialTick, float ageInTicks) {
        try {
            if (v == null || driverHumanoid == null) return;

            boolean forced = isEatingPoseForced(v);

            ItemStack using;
            InteractionHand hand;

            if (forced) {
                // If we managed to start using the item locally, let vanilla animation run instead.
                if (v.isUsingItem()) return;
                using = v.getMainHandItem();
                hand = InteractionHand.MAIN_HAND;
            } else {
                // If vanilla is correctly syncing "using item", apply the eat pose when UseAnim is EAT.
                // This makes the third-person arms animation visible on our custom arms layer.
                if (!v.isUsingItem()) return;
                using = v.getUseItem();
                hand = v.getUsedItemHand();
            }

            if (using == null || using.isEmpty()) return;
            if (using.getUseAnimation() != UseAnim.EAT) return;

            HumanoidArm mainArm = v.getMainArm();
            boolean usingMainHand = (hand == InteractionHand.MAIN_HAND);

            boolean activeIsRight = usingMainHand
                    ? (mainArm == HumanoidArm.RIGHT)
                    : (mainArm != HumanoidArm.RIGHT);

            ModelPart arm = activeIsRight ? driverHumanoid.rightArm : driverHumanoid.leftArm;
            if (arm == null) return;

            float t = ageInTicks + partialTick;
            float wobble = Mth.cos(t * EAT_WOBBLE_SPEED) * EAT_XROT_WOBBLE;

            // Raise hand to mouth with a small wobble.
            arm.xRot = EAT_XROT_BASE + wobble;

            // Rotate inward toward face.
            arm.yRot += activeIsRight ? -EAT_YROT_IN : EAT_YROT_IN;

            // Small twist for "bite" motion.
            arm.zRot += activeIsRight ? -EAT_ZROT_TWIST : EAT_ZROT_TWIST;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] applyManualEatPoseToDriverArms failed (soft): {}", t.toString());
        }
    }

    private static boolean isEatingPoseForced(Villager v) {
        try {
            if (v == null) return false;
            if (v instanceof VillagerOverhaulRenderAccess acc) {
                return VillagerRenderFlags.renderEatingPose(acc.ezvr$getRenderFlags());
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Smoothstep easing in [0..1].
     * - preserves endpoints (0->0, 1->1)
     * - slows the first visible frame so the swing doesn't "start too high"
     */
    private static float smoothStep01(float t) {
        if (Float.isNaN(t) || Float.isInfinite(t)) return 0.0f;
        if (t < 0.0f) t = 0.0f;
        if (t > 1.0f) t = 1.0f;
        return t * t * (3.0f - 2.0f * t);
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

    private static void logClientSwingProof(Villager v, float swingProg) {
        try {
            if (v == null) return;

            UUID id = v.getUUID();
            if (id == null) return;

            int tick = v.tickCount;
            Integer last = LAST_INFO_TICK.get(id);
            if (last != null && (tick - last) < INFO_LOG_INTERVAL_TICKS) return;
            LAST_INFO_TICK.put(id, tick);

            ItemStack main = v.getMainHandItem();
            if (main == null || main.isEmpty()) return;

            int seq = 0;
            if (v instanceof VillagerOverhaulSwingAccess acc) seq = acc.ezvr$getSwingSeq();

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] [client] swingStateSeq villager={} tick={} seq={} prog={} mainItem={}",
                    id,
                    tick,
                    seq,
                    fmt3(swingProg),
                    String.valueOf(main.getItem())
            );
        } catch (Throwable ignored) {}
    }

    private static String fmt3(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return "0";
        return String.valueOf(Math.round(v * 1000.0f) / 1000.0f);
    }
}
