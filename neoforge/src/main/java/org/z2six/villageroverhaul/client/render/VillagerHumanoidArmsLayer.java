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

    /**
     * Tighten swing cone by ~20%.
     */
    private static final float SWING_CONE_SCALE = 0.80f;

    // (Kept) manual eat pose tuning – OFF by default because you want vanilla.
    private static final float EAT_XROT_BASE = -1.55f;
    private static final float EAT_XROT_WOBBLE = 0.10f;
    private static final float EAT_YROT_IN = 0.60f;
    private static final float EAT_ZROT_TWIST = 0.16f;
    private static final float EAT_WOBBLE_SPEED = 0.70f;

    private static final boolean ENABLE_EASED_PROGRESS = true;

    // Prefer vanilla use/eat animation (HumanoidModel uses active use stack + remaining ticks).
    private static final boolean PREFER_VANILLA_EAT = true;

    // Manual fallback (keep false unless you want to test forced pose again).
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

    // Keep this on until fixed; it logs one line per new seed key.
    private static final boolean DEBUG_USE_SEED_LOG = true;

    // Reflection fallback: directly patch LivingEntity.useItem + LivingEntity.useItemRemaining on CLIENT
    // when vanilla state refuses to update (we observed remTicks=0 and activeUse=wooden_sword).
    private static volatile boolean USE_FIELDS_SCANNED = false;
    private static volatile Field FIELD_USE_ITEM = null;          // expected: LivingEntity.useItem
    private static volatile Field FIELD_USE_ITEM_REMAINING = null; // expected: LivingEntity.useItemRemaining

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

            // Let vanilla setupAnim compute arm rotations (including vanilla use/eat motion if active-use state is valid).
            driverHumanoid.setupAnim(villager, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            if (ENABLE_MANUAL_EAT_POSE_FALLBACK) {
                applyManualEatPoseToDriverArms(villager, partialTick, ageInTicks);
            }

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

    private void setupDriverState(Villager v, float swingProg) {
        try {
            if (v == null || driverHumanoid == null) return;

            if (PREFER_VANILLA_EAT) {
                // Critical: make sure active-use stack + remaining ticks are correct on CLIENT so vanilla anim can run.
                seedClientActiveUseIfNeeded(v);
            }

            driverHumanoid.attackTime = swingProg;
            driverHumanoid.riding = v.isPassenger();
            driverHumanoid.young = v.isBaby();

            driverHumanoid.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            driverHumanoid.rightArmPose = HumanoidModel.ArmPose.EMPTY;

            boolean forced = isEatingPoseForced(v);

            boolean usingFlag = false;
            try { usingFlag = v.isUsingItem(); } catch (Throwable ignored) { usingFlag = false; }

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            try {
                InteractionHand h = v.getUsedItemHand();
                if (h != null) usedHand = h;
            } catch (Throwable ignored) {}

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

                if (activeIsRight) driverHumanoid.rightArmPose = pose;
                else driverHumanoid.leftArmPose = pose;

                // Suppress swing if using any item.
                driverHumanoid.attackTime = 0.0f;
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] setupDriverState failed (soft): {}", t.toString());
        }
    }

    private void applyManualSwingToDriverArms(Villager v, float p) {
        try {
            if (v == null || driverHumanoid == null) return;

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
            ModelPart arm = (armToSwing == HumanoidArm.RIGHT) ? driverHumanoid.rightArm : driverHumanoid.leftArm;
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

    private void applyManualEatPoseToDriverArms(Villager v, float partialTick, float ageInTicks) {
        try {
            if (v == null || driverHumanoid == null) return;

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

            ModelPart arm = activeIsRight ? driverHumanoid.rightArm : driverHumanoid.leftArm;
            if (arm == null) return;

            float t = ageInTicks + partialTick;
            float wobble = Mth.cos(t * EAT_WOBBLE_SPEED) * EAT_XROT_WOBBLE;

            arm.xRot = EAT_XROT_BASE + wobble;
            arm.yRot += activeIsRight ? -EAT_YROT_IN : EAT_YROT_IN;
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

    private static ItemStack getHandStackSafe(Villager v, InteractionHand hand) {
        try {
            if (v == null || hand == null) return ItemStack.EMPTY;
            ItemStack st = v.getItemInHand(hand);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Fix for your exact log:
     *   activeUse=minecraft:wooden_sword remTicks=0 while held=cooked_beef and isUsingItem=true
     *
     * Root cause (client-side):
     * - use flags may be synced, but LivingEntity.useItem + useItemRemaining can remain stale/zero for mobs.
     *
     * Strategy:
     * 1) If we're "using" and held item is EAT but active use item isn't EAT or remaining ticks <= 0:
     *    - call stopUsingItem() client-side (to break "already using" short-circuit)
     *    - call startUsingItem(hand) client-side
     * 2) If remaining ticks is still <= 0, use reflection to directly set:
     *    - LivingEntity.useItem = held
     *    - LivingEntity.useItemRemaining = held.getUseDuration(entity) (fallback 32)
     *    Then call startUsingItem(hand) again to set flags consistently.
     */
    private static void seedClientActiveUseIfNeeded(Villager v) {
        try {
            if (v == null) return;

            boolean usingFlag = false;
            try { usingFlag = v.isUsingItem(); } catch (Throwable ignored) { usingFlag = false; }
            if (!usingFlag) return;

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            try {
                InteractionHand h = v.getUsedItemHand();
                if (h != null) usedHand = h;
            } catch (Throwable ignored) {}

            ItemStack held = getHandStackSafe(v, usedHand);
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

            // If client already thinks we're eating with a proper timer, do nothing.
            if (activeIsEat && rem > 0) return;

            UUID id = v.getUUID();
            if (id == null) return;

            int tick = v.tickCount;

            Integer lastTick = LAST_USE_SEED_TICK.get(id);
            if (lastTick != null && (tick - lastTick) < USE_SEED_MIN_INTERVAL_TICKS) return;

            boolean forced = isEatingPoseForced(v);
            String key = usedHand.name() + "|" + safeItemName(held) + "|forced=" + forced;

            String lastKey = LAST_USE_SEED_KEY.get(id);
            boolean newKey = (lastKey == null) || (!lastKey.equals(key));

            LAST_USE_SEED_TICK.put(id, tick);
            LAST_USE_SEED_KEY.put(id, key);

            // Step 1: break potential "already using" early-return, then restart using with correct hand.
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

            // Step 2 (fallback): if still not a valid eat state, patch fields directly.
            if (!(activeAfterEat && remAfter > 0)) {
                warmupUseFields();

                int dur = 32;
                try {
                    // Mojmap: ItemStack#getUseDuration(LivingEntity) exists in modern versions.
                    dur = held.getUseDuration(v);
                } catch (Throwable ignored) { dur = 32; }
                if (dur <= 0) dur = 32;

                try {
                    if (FIELD_USE_ITEM != null) {
                        FIELD_USE_ITEM.set(v, held.copy());
                    }
                    if (FIELD_USE_ITEM_REMAINING != null) {
                        FIELD_USE_ITEM_REMAINING.setInt(v, dur);
                    }
                } catch (Throwable ignored) {}

                // Re-assert using flags now that fields are patched.
                try { v.startUsingItem(usedHand); } catch (Throwable ignored) {}

                try { remAfter = v.getUseItemRemainingTicks(); } catch (Throwable ignored) { remAfter = remAfter; }
                try {
                    ItemStack ui3 = v.getUseItem();
                    activeAfter = (ui3 == null) ? ItemStack.EMPTY : ui3;
                } catch (Throwable ignored) { /* keep */ }
            }

            if (DEBUG_USE_SEED_LOG && newKey) {
                int remNow = 0;
                ItemStack activeNow = ItemStack.EMPTY;
                try { remNow = v.getUseItemRemainingTicks(); } catch (Throwable ignored) { remNow = 0; }
                try {
                    ItemStack uiN = v.getUseItem();
                    activeNow = (uiN == null) ? ItemStack.EMPTY : uiN;
                } catch (Throwable ignored) { activeNow = ItemStack.EMPTY; }

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] eat_use_seed vill={} tick={} hand={} held={} activeUse={} remTicks={} forcedFlag={} note={}",
                        id,
                        tick,
                        usedHand.name(),
                        safeItemName(held),
                        (activeNow.isEmpty() ? "empty" : safeItemName(activeNow)),
                        remNow,
                        forced,
                        (remNow > 0 ? "ok" : "still_zero")
                );
            }

        } catch (Throwable ignored) {}
    }

    private static void warmupUseFields() {
        if (USE_FIELDS_SCANNED) return;
        USE_FIELDS_SCANNED = true;
        try {
            // Mojmap (NeoForge docs / javadocs show these exact field names in many versions):
            //   protected ItemStack useItem;
            //   protected int useItemRemaining;
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

            // Food/eat returns ITEM pose; the actual eat motion comes from vanilla "use" state/timer.
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
