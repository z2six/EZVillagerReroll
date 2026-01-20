// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHumanoidArmsLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
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
import java.lang.reflect.Field;
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

    // Local animation window for a single swing
    private static final int LOCAL_SWING_DURATION_TICKS = 6;

    // Manual swing tuning
    private static final float SWING_XROT_SCALE = 1.20f;
    private static final float SWING_ZROT_SCALE = 0.40f;
    private static final float SWING_CONE_SCALE = 0.80f;

    // ---------------------------------------------------------------------
    // EAT OVERRIDE (THIS IS THE REAL FIX)
    // ---------------------------------------------------------------------

    /**
     * Vanilla/driver eat pose is too subtle for villagers in your pipeline (confirmed by your driver logs:
     * rRot around -0.33..-0.36, tiny zRot drift).
     *
     * So we override the arm into a clear "hand to mouth" pose during UseAnim.EAT.
     */
    private static final boolean ENABLE_EAT_OVERRIDE = true;

    /**
     * How strongly we override the arm pose.
     * 1.0 = fully our pose; 0.0 = do nothing.
     */
    private static final float EAT_OVERRIDE_BLEND = 1.0f;

    /**
     * Base target pose for the active (eating) arm.
     * These are intentionally strong so you can actually see it.
     */
    private static final float EAT_TARGET_XROT = -1.35f;
    private static final float EAT_TARGET_YROT_IN = 0.55f;
    private static final float EAT_TARGET_ZROT_TWIST = 0.18f;

    /**
     * Small wobble so the bite looks alive.
     */
    private static final float EAT_WOBBLE_SPEED = 0.70f;
    private static final float EAT_WOBBLE_XROT = 0.10f;

    /**
     * Optional secondary arm response (subtle).
     */
    private static final boolean EAT_MOVE_OTHER_ARM_SLIGHTLY = true;
    private static final float EAT_OTHER_ARM_XROT = -0.25f;
    private static final float EAT_OTHER_ARM_ZROT = 0.04f;

    private static final boolean ENABLE_EASED_PROGRESS = true;

    /**
     * Driver model for vanilla arm logic.
     */
    private static final boolean USE_PLAYERMODEL_DRIVER_FOR_VANILLA_EAT = true;

    /**
     * Prefer vanilla use/eat animation. We will seed client use state if it is stale.
     */
    private static final boolean PREFER_VANILLA_EAT = true;

    /**
     * Manual fallback pose (kept but OFF — we now do a better targeted override above).
     */
    private static final boolean ENABLE_MANUAL_EAT_POSE_FALLBACK = false;

    // INFO throttle
    private static final int INFO_LOG_INTERVAL_TICKS = 40;
    private static final Map<UUID, Integer> LAST_INFO_TICK = new HashMap<>();

    // Per-entity swing tracking
    private static final Map<Integer, SwingTrack> SWING_TRACK = new HashMap<>();

    // Client-side use-seed throttling (avoid spamming stop/startUsingItem)
    private static final Map<UUID, Integer> LAST_USE_SEED_TICK = new HashMap<>();
    private static final Map<UUID, String> LAST_USE_SEED_KEY = new HashMap<>();
    private static final int USE_SEED_MIN_INTERVAL_TICKS = 6;

    private static final boolean DEBUG_USE_SEED_LOG = true;

    // Reflection fallback: directly patch LivingEntity.useItem + LivingEntity.useItemRemaining on CLIENT
    private static volatile boolean USE_FIELDS_SCANNED = false;
    private static volatile Field FIELD_USE_ITEM = null;           // LivingEntity.useItem
    private static volatile Field FIELD_USE_ITEM_REMAINING = null; // LivingEntity.useItemRemaining

    private static final class SwingTrack {
        int lastSeq = 0;
        long localStartGameTime = -1L;
        WeakReference<Villager> ref;

        SwingTrack(Villager v) {
            this.ref = new WeakReference<>(v);
        }
    }

    private final VillagerCombatArmsModel armsModel;

    private final HumanoidModel<LivingEntity> injectedDriverHumanoid;
    private volatile PlayerModel<LivingEntity> playerDriver = null;

    private boolean ezvr$initLogged = false;
    private boolean ezvr$driverLogged = false;

    // Debug: driver pose logs (your recently-added diagnostic)
    private static final Map<UUID, Integer> LAST_EATPOSE_LOG_TICK = new HashMap<>();

    // Debug: eat override application logs
    private static final Map<UUID, Integer> LAST_EAT_OVERRIDE_LOG_TICK = new HashMap<>();

    public VillagerHumanoidArmsLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                     VillagerCombatArmsModel armsModel,
                                     HumanoidModel<LivingEntity> driverHumanoid) {
        super(parent);
        this.armsModel = armsModel;
        this.injectedDriverHumanoid = driverHumanoid;
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
            if (villager == null || armsModel == null) return;

            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VillagerHumanoidArmsLayer ACTIVE (seq-driven swing)");
            }

            if (!shouldRenderCustomArms(villager)) return;

            HumanoidModel<LivingEntity> driver = getDriverModel();
            if (driver == null) return;

            if (!ezvr$driverLogged) {
                ezvr$driverLogged = true;
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] ArmsLayer driverModel={}", driver.getClass().getName());
            }

            float swingProg = computeSeqDrivenSwingProgress(villager, partialTick);

            setupDriverState(driver, villager, swingProg);

            // Vanilla flow
            driver.prepareMobModel(villager, limbSwing, limbSwingAmount, partialTick);
            driver.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            // Debug: prove the driver is doing *something* (you already saw it move slightly).
            debugLogDriverEatPose(villager, driver);

            // Optional manual fallback (kept).
            if (ENABLE_MANUAL_EAT_POSE_FALLBACK) {
                applyManualEatPoseToDriverArms(driver, villager, partialTick, ageInTicks);
            }

            // Strong eat override: make it visibly "hand to mouth".
            if (ENABLE_EAT_OVERRIDE) {
                applyEatOverrideIfEating(driver, villager, partialTick, ageInTicks);
            }

            applyManualSwingToDriverArms(driver, villager, swingProg);

            // Copy to custom arms model
            armsModel.setArmRotationsFromHumanoid(driver.rightArm, driver.leftArm);

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

    private HumanoidModel<LivingEntity> getDriverModel() {
        try {
            if (!USE_PLAYERMODEL_DRIVER_FOR_VANILLA_EAT) return injectedDriverHumanoid;

            PlayerModel<LivingEntity> pd = playerDriver;
            if (pd != null) return pd;

            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return injectedDriverHumanoid;

                ModelPart root = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER);
                pd = new PlayerModel<>(root, false);
                playerDriver = pd;
                return pd;
            } catch (Throwable t) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] ArmsLayer PlayerModel init failed (soft): {}", t.toString());
                return injectedDriverHumanoid;
            }
        } catch (Throwable ignored) {
            return injectedDriverHumanoid;
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

            if (seq != tr.lastSeq) {
                int prev = tr.lastSeq;
                tr.lastSeq = seq;
                tr.localStartGameTime = now;

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

    private void setupDriverState(HumanoidModel<LivingEntity> driver, Villager v, float swingProg) {
        try {
            if (driver == null || v == null) return;

            boolean forced = isEatingPoseForced(v);

            if (PREFER_VANILLA_EAT) {
                seedClientActiveUseIfNeeded(v, forced);
            }

            driver.attackTime = swingProg;
            driver.riding = v.isPassenger();
            driver.young = v.isBaby();

            driver.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            driver.rightArmPose = HumanoidModel.ArmPose.EMPTY;

            boolean usingFlag = false;
            try { usingFlag = v.isUsingItem(); } catch (Throwable ignored) { usingFlag = false; }

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            if (!forced) {
                try {
                    InteractionHand h = v.getUsedItemHand();
                    if (h != null) usedHand = h;
                } catch (Throwable ignored) {}
            }

            ItemStack heldInUsedHand = getHandStackSafe(v, usedHand);
            ItemStack mainHand = getHandStackSafe(v, InteractionHand.MAIN_HAND);
            ItemStack effectiveUsing = (!heldInUsedHand.isEmpty()) ? heldInUsedHand : mainHand;

            boolean shouldTreatAsUsing = usingFlag || forced;

            if (shouldTreatAsUsing) {
                HumanoidArm mainArm = v.getMainArm();
                boolean usingMainHand = (usedHand == InteractionHand.MAIN_HAND);

                boolean activeIsRight = usingMainHand
                        ? (mainArm == HumanoidArm.RIGHT)
                        : (mainArm != HumanoidArm.RIGHT);

                HumanoidModel.ArmPose pose = armPoseFor(effectiveUsing);

                if (activeIsRight) driver.rightArmPose = pose;
                else driver.leftArmPose = pose;

                // Suppress swing if using any item.
                driver.attackTime = 0.0f;
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] setupDriverState failed (soft): {}", t.toString());
        }
    }

    private void applyManualSwingToDriverArms(HumanoidModel<LivingEntity> driver, Villager v, float p) {
        try {
            if (driver == null || v == null) return;

            boolean using = false;
            try { using = v.isUsingItem(); } catch (Throwable ignored) { using = false; }
            if (using) return;
            if (isEatingPoseForced(v)) return;

            if (p <= 0.0001f) return;

            float pe = p;
            if (ENABLE_EASED_PROGRESS) {
                pe = smoothStep01(p);
            }

            HumanoidArm armToSwing = v.getMainArm();
            ModelPart arm = (armToSwing == HumanoidArm.RIGHT) ? driver.rightArm : driver.leftArm;
            if (arm == null) return;

            float f1 = Mth.sin(pe * (float) Math.PI);

            float eased = 1.0F - (1.0F - pe) * (1.0F - pe);
            float f2 = Mth.sin(eased * (float) Math.PI);

            float cone = SWING_CONE_SCALE;

            arm.xRot -= (f1 * (SWING_XROT_SCALE * cone) + f2 * (0.4f * cone));

            float twist = Mth.sin(pe * (float) Math.PI) * (SWING_ZROT_SCALE * cone);
            if (armToSwing == HumanoidArm.RIGHT) arm.zRot -= twist;
            else arm.zRot += twist;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] applyManualSwingToDriverArms failed (soft): {}", t.toString());
        }
    }

    /**
     * Strong, deterministic eat animation. This is applied *after* vanilla setupAnim() so it wins.
     */
    private void applyEatOverrideIfEating(HumanoidModel<LivingEntity> driver, Villager v, float partialTick, float ageInTicks) {
        try {
            if (driver == null || v == null) return;

            EatCtx ctx = getEatContext(v);
            if (!ctx.isEating) return;

            // Determine active arm from used hand + main arm.
            HumanoidArm mainArm = v.getMainArm();
            boolean usingMainHand = (ctx.usedHand == InteractionHand.MAIN_HAND);

            boolean activeIsRight = usingMainHand
                    ? (mainArm == HumanoidArm.RIGHT)
                    : (mainArm != HumanoidArm.RIGHT);

            ModelPart activeArm = activeIsRight ? driver.rightArm : driver.leftArm;
            ModelPart otherArm = activeIsRight ? driver.leftArm : driver.rightArm;
            if (activeArm == null) return;

            // Progress 0..1 (0 = start, 1 = end) using remaining ticks.
            float p = ctx.progress01;
            if (ENABLE_EASED_PROGRESS) p = smoothStep01(p);

            // Wobble
            float t = ageInTicks + partialTick;
            float wobble = Mth.cos(t * EAT_WOBBLE_SPEED) * EAT_WOBBLE_XROT;

            // Target pose
            float targetX = EAT_TARGET_XROT + wobble;
            float targetY = (activeIsRight ? -EAT_TARGET_YROT_IN : EAT_TARGET_YROT_IN);
            float targetZ = (activeIsRight ? -EAT_TARGET_ZROT_TWIST : EAT_TARGET_ZROT_TWIST);

            // Some bite-like pulsing near the end of the cycle
            float bite = Mth.sin(p * (float) Math.PI);
            targetX += bite * 0.15f;

            // Blend into the target (so you can dial it down if needed)
            float blend = EAT_OVERRIDE_BLEND;
            activeArm.xRot = lerp(activeArm.xRot, targetX, blend);
            activeArm.yRot = lerp(activeArm.yRot, targetY, blend);
            activeArm.zRot = lerp(activeArm.zRot, targetZ, blend);

            if (EAT_MOVE_OTHER_ARM_SLIGHTLY && otherArm != null) {
                float ox = EAT_OTHER_ARM_XROT;
                float oz = activeIsRight ? EAT_OTHER_ARM_ZROT : -EAT_OTHER_ARM_ZROT;

                otherArm.xRot = lerp(otherArm.xRot, ox, blend * 0.35f);
                otherArm.zRot = lerp(otherArm.zRot, oz, blend * 0.35f);
            }

            debugLogEatOverride(v, ctx, activeIsRight, activeArm, otherArm);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] applyEatOverrideIfEating failed (soft): {}", t.toString());
        }
    }

    private static final class EatCtx {
        final boolean isEating;
        final InteractionHand usedHand;
        final float progress01;

        EatCtx(boolean isEating, InteractionHand usedHand, float progress01) {
            this.isEating = isEating;
            this.usedHand = usedHand;
            this.progress01 = progress01;
        }
    }

    private static EatCtx getEatContext(Villager v) {
        try {
            if (v == null) return new EatCtx(false, InteractionHand.MAIN_HAND, 0.0f);

            boolean forced = isEatingPoseForced(v);

            boolean using = false;
            try { using = v.isUsingItem(); } catch (Throwable ignored) { using = false; }

            // Determine which item is being used
            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            if (using && !forced) {
                try {
                    InteractionHand h = v.getUsedItemHand();
                    if (h != null) usedHand = h;
                } catch (Throwable ignored) {}
            }

            ItemStack held = getHandStackSafe(v, usedHand);
            if (held.isEmpty() && forced) {
                usedHand = InteractionHand.MAIN_HAND;
                held = getHandStackSafe(v, InteractionHand.MAIN_HAND);
            }

            boolean isEatAnim = false;
            try { isEatAnim = !held.isEmpty() && held.getUseAnimation() == UseAnim.EAT; } catch (Throwable ignored) { isEatAnim = false; }

            boolean eatActive = forced || (using && isEatAnim);
            if (!eatActive) return new EatCtx(false, InteractionHand.MAIN_HAND, 0.0f);

            int rem = 0;
            try { rem = v.getUseItemRemainingTicks(); } catch (Throwable ignored) { rem = 0; }

            int dur = 32;
            try { dur = held.getUseDuration(v); } catch (Throwable ignored) { dur = 32; }
            if (dur <= 0) dur = 32;

            // progress: 0 at start (rem=dur), 1 at end (rem~0)
            float p = 0.0f;
            try {
                p = 1.0f - (rem / (float) dur);
            } catch (Throwable ignored) {
                p = 0.0f;
            }

            if (Float.isNaN(p) || Float.isInfinite(p)) p = 0.0f;
            if (p < 0.0f) p = 0.0f;
            if (p > 1.0f) p = 1.0f;

            return new EatCtx(true, usedHand, p);

        } catch (Throwable ignored) {
            return new EatCtx(false, InteractionHand.MAIN_HAND, 0.0f);
        }
    }

    private static void debugLogEatOverride(Villager v, EatCtx ctx, boolean activeIsRight, ModelPart activeArm, ModelPart otherArm) {
        try {
            if (v == null || ctx == null) return;
            UUID id = v.getUUID();
            if (id == null) return;

            int tick = v.tickCount;
            Integer last = LAST_EAT_OVERRIDE_LOG_TICK.get(id);
            if (last != null && (tick - last) < 10) return;
            LAST_EAT_OVERRIDE_LOG_TICK.put(id, tick);

            float ax = activeArm == null ? 0 : activeArm.xRot;
            float ay = activeArm == null ? 0 : activeArm.yRot;
            float az = activeArm == null ? 0 : activeArm.zRot;

            float ox = otherArm == null ? 0 : otherArm.xRot;
            float oy = otherArm == null ? 0 : otherArm.yRot;
            float oz = otherArm == null ? 0 : otherArm.zRot;

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] [client] eat_override vill={} tick={} hand={} activeIsRight={} prog={} aRot=({},{},{}) oRot=({},{},{})",
                    id,
                    tick,
                    ctx.usedHand.name(),
                    activeIsRight,
                    fmt3(ctx.progress01),
                    fmt3(ax), fmt3(ay), fmt3(az),
                    fmt3(ox), fmt3(oy), fmt3(oz)
            );
        } catch (Throwable ignored) {}
    }

    private static void debugLogDriverEatPose(Villager v, HumanoidModel<LivingEntity> driver) {
        try {
            if (v == null || driver == null) return;

            boolean using = false;
            try { using = v.isUsingItem(); } catch (Throwable ignored) { using = false; }

            boolean forced = isEatingPoseForced(v);
            if (!using && !forced) return;

            UUID id = v.getUUID();
            if (id == null) return;

            int tick = v.tickCount;
            Integer last = LAST_EATPOSE_LOG_TICK.get(id);
            if (last != null && (tick - last) < 5) return;
            LAST_EATPOSE_LOG_TICK.put(id, tick);

            ModelPart r = driver.rightArm;
            ModelPart l = driver.leftArm;

            float rx = (r == null) ? 0 : r.xRot;
            float ry = (r == null) ? 0 : r.yRot;
            float rz = (r == null) ? 0 : r.zRot;

            float lx = (l == null) ? 0 : l.xRot;
            float ly = (l == null) ? 0 : l.yRot;
            float lz = (l == null) ? 0 : l.zRot;

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] [client] eat_driver_pose vill={} tick={} using={} forced={} rRot=({},{},{}) lRot=({},{},{})",
                    id, tick, using, forced,
                    fmt3(rx), fmt3(ry), fmt3(rz),
                    fmt3(lx), fmt3(ly), fmt3(lz)
            );
        } catch (Throwable ignored) {}
    }

    private static void applyManualEatPoseToDriverArms(HumanoidModel<LivingEntity> driver, Villager v, float partialTick, float ageInTicks) {
        try {
            if (driver == null || v == null) return;

            boolean forced = isEatingPoseForced(v);

            ItemStack using;
            InteractionHand hand;

            if (forced) {
                boolean usingFlag = false;
                try { usingFlag = v.isUsingItem(); } catch (Throwable ignored) { usingFlag = false; }
                if (usingFlag) return;

                using = getHandStackSafe(v, InteractionHand.MAIN_HAND);
                hand = InteractionHand.MAIN_HAND;
            } else {
                boolean usingFlag = false;
                try { usingFlag = v.isUsingItem(); } catch (Throwable ignored) { usingFlag = false; }
                if (!usingFlag) return;

                ItemStack ui = ItemStack.EMPTY;
                try {
                    ItemStack tmp = v.getUseItem();
                    ui = (tmp == null) ? ItemStack.EMPTY : tmp;
                } catch (Throwable ignored) { ui = ItemStack.EMPTY; }

                if (!ui.isEmpty()) using = ui;
                else {
                    InteractionHand uh = InteractionHand.MAIN_HAND;
                    try {
                        InteractionHand tmp = v.getUsedItemHand();
                        if (tmp != null) uh = tmp;
                    } catch (Throwable ignored) {}
                    using = getHandStackSafe(v, uh);
                }

                InteractionHand uh = InteractionHand.MAIN_HAND;
                try {
                    InteractionHand tmp = v.getUsedItemHand();
                    if (tmp != null) uh = tmp;
                } catch (Throwable ignored) {}
                hand = uh;
            }

            if (using == null || using.isEmpty()) return;
            if (using.getUseAnimation() != UseAnim.EAT) return;

            HumanoidArm mainArm = v.getMainArm();
            boolean usingMainHand = (hand == InteractionHand.MAIN_HAND);

            boolean activeIsRight = usingMainHand
                    ? (mainArm == HumanoidArm.RIGHT)
                    : (mainArm != HumanoidArm.RIGHT);

            ModelPart arm = activeIsRight ? driver.rightArm : driver.leftArm;
            if (arm == null) return;

            float t = ageInTicks + partialTick;
            float wobble = Mth.cos(t * EAT_WOBBLE_SPEED) * EAT_WOBBLE_XROT;

            arm.xRot = (-1.55f) + wobble;
            arm.yRot += activeIsRight ? -0.60f : 0.60f;
            arm.zRot += activeIsRight ? -0.16f : 0.16f;

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

    private static ItemStack getHandStackSafe(Villager v, InteractionHand hand) {
        try {
            if (v == null || hand == null) return ItemStack.EMPTY;
            ItemStack st = v.getItemInHand(hand);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static void seedClientActiveUseIfNeeded(Villager v, boolean forcedEatingFlag) {
        try {
            if (v == null) return;

            boolean usingFlag = false;
            try { usingFlag = v.isUsingItem(); } catch (Throwable ignored) { usingFlag = false; }

            if (!usingFlag && !forcedEatingFlag) return;

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            if (usingFlag) {
                try {
                    InteractionHand h = v.getUsedItemHand();
                    if (h != null) usedHand = h;
                } catch (Throwable ignored) {}
            }

            ItemStack held = getHandStackSafe(v, usedHand);
            if (held.isEmpty() && forcedEatingFlag) {
                held = getHandStackSafe(v, InteractionHand.MAIN_HAND);
                usedHand = InteractionHand.MAIN_HAND;
            }

            if (held.isEmpty()) return;
            if (held.getUseAnimation() != UseAnim.EAT) return;

            ItemStack active = ItemStack.EMPTY;
            try {
                ItemStack ui = v.getUseItem();
                active = (ui == null) ? ItemStack.EMPTY : ui;
            } catch (Throwable ignored) { active = ItemStack.EMPTY; }

            int rem = 0;
            try { rem = v.getUseItemRemainingTicks(); } catch (Throwable ignored) { rem = 0; }

            boolean activeIsEat = (!active.isEmpty() && active.getUseAnimation() == UseAnim.EAT);
            if (activeIsEat && rem > 0) return;

            UUID id = v.getUUID();
            if (id == null) return;

            int tick = v.tickCount;

            Integer lastTick = LAST_USE_SEED_TICK.get(id);
            if (lastTick != null && (tick - lastTick) < USE_SEED_MIN_INTERVAL_TICKS) return;

            String key = usedHand.name() + "|" + safeItemName(held) + "|forced=" + forcedEatingFlag;
            String lastKey = LAST_USE_SEED_KEY.get(id);
            boolean newKey = (lastKey == null) || (!lastKey.equals(key));

            LAST_USE_SEED_TICK.put(id, tick);
            LAST_USE_SEED_KEY.put(id, key);

            try { v.stopUsingItem(); } catch (Throwable ignored) {}
            try { v.startUsingItem(usedHand); } catch (Throwable ignored) {}

            int remAfter = 0;
            ItemStack activeAfter = ItemStack.EMPTY;
            try { remAfter = v.getUseItemRemainingTicks(); } catch (Throwable ignored) { remAfter = 0; }
            try {
                ItemStack ui2 = v.getUseItem();
                activeAfter = (ui2 == null) ? ItemStack.EMPTY : ui2;
            } catch (Throwable ignored) { activeAfter = ItemStack.EMPTY; }

            boolean activeAfterEat = (!activeAfter.isEmpty() && activeAfter.getUseAnimation() == UseAnim.EAT);

            if (!(activeAfterEat && remAfter > 0)) {
                warmupUseFields();

                int dur = 32;
                try { dur = held.getUseDuration(v); } catch (Throwable ignored) { dur = 32; }
                if (dur <= 0) dur = 32;

                try {
                    if (FIELD_USE_ITEM != null) FIELD_USE_ITEM.set(v, held.copy());
                    if (FIELD_USE_ITEM_REMAINING != null) FIELD_USE_ITEM_REMAINING.setInt(v, dur);
                } catch (Throwable ignored) {}

                try { v.startUsingItem(usedHand); } catch (Throwable ignored) {}
            }

            if (DEBUG_USE_SEED_LOG && newKey) {
                int remNow = 0;
                ItemStack activeNow = ItemStack.EMPTY;
                boolean usingNow = false;

                try { remNow = v.getUseItemRemainingTicks(); } catch (Throwable ignored) { remNow = 0; }
                try {
                    ItemStack uiN = v.getUseItem();
                    activeNow = (uiN == null) ? ItemStack.EMPTY : uiN;
                } catch (Throwable ignored) { activeNow = ItemStack.EMPTY; }
                try { usingNow = v.isUsingItem(); } catch (Throwable ignored) { usingNow = false; }

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] eat_use_seed vill={} tick={} hand={} held={} activeUse={} remTicks={} forcedFlag={} usingNow={} note={}",
                        id,
                        tick,
                        usedHand.name(),
                        safeItemName(held),
                        (activeNow.isEmpty() ? "empty" : safeItemName(activeNow)),
                        remNow,
                        forcedEatingFlag,
                        usingNow,
                        (remNow > 0 && !activeNow.isEmpty() ? "ok" : "still_bad")
                );
            }

        } catch (Throwable ignored) {}
    }

    private static void warmupUseFields() {
        if (USE_FIELDS_SCANNED) return;
        USE_FIELDS_SCANNED = true;
        try {
            Field fUse = null;
            Field fRem = null;

            try {
                fUse = LivingEntity.class.getDeclaredField("useItem");
                fUse.setAccessible(true);
            } catch (Throwable ignored) { fUse = null; }

            try {
                fRem = LivingEntity.class.getDeclaredField("useItemRemaining");
                fRem.setAccessible(true);
            } catch (Throwable ignored) { fRem = null; }

            FIELD_USE_ITEM = fUse;
            FIELD_USE_ITEM_REMAINING = fRem;

        } catch (Throwable ignored) {
            FIELD_USE_ITEM = null;
            FIELD_USE_ITEM_REMAINING = null;
        }
    }

    private static String safeItemName(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "empty";
            return String.valueOf(st.getItem());
        } catch (Throwable ignored) {
            return "error";
        }
    }

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

    private static float lerp(float a, float b, float t) {
        if (Float.isNaN(a) || Float.isNaN(b) || Float.isNaN(t)) return b;
        if (t <= 0.0f) return a;
        if (t >= 1.0f) return b;
        return a + (b - a) * t;
    }
}
