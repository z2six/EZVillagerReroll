// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDirector.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.menu.VillagerInventoryMenu;
import org.z2six.villageroverhaul.server.CombatSettingsService;
import org.z2six.villageroverhaul.server.FarmingSettingsService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class VillagerCombatDirector {

    private static final boolean ENABLE_BLOCKING = true;

    private static final double MOVE_SPEED = 0.70;
    private static final double BACKPEDAL_SPEED = MOVE_SPEED * 0.25;
    private static final double BASE_REACH = 2.0;
    private static final double SAFETY_MARGIN = 0.5;

    private static final float EAT_HP_THRESHOLD = 0.65f;
    private static final float PASSIVE_EAT_HP_THRESHOLD = 0.80f;
    private static final float EAT_MOVE_SLOW_MULT = 0.25f;
    private static final double EAT_BACKPEDAL_SPEED = 0.50;
    private static final double EAT_RUN_SPEED = 0.65;
    private static final double EAT_DIST_RUN_START = 3.0;
    private static final double EAT_DIST_START_EATING = 5.0;
    private static final int EAT_MAX_RESETS = 1;
    private static final int EAT_BACKPEDAL_HIT_FORCE_EAT = 2;
    private static final double EAT_BACKPEDAL_FORCE_EAT_SPEED = 0.50;

    // Combat circling (strafing) tuning. These are "input" speeds, not raw blocks/tick.
    private static final float CIRCLE_SPEED = 0.12f;

    private static final String PD_BLOCKED_TICK = "ezvr_blocked_tick";
    private static final String PD_LOCK_YAW_UNTIL = "ezvr_lock_yaw_until";
    private static final String PD_LOCK_YAW = "ezvr_lock_yaw";
    private static final String PD_BACKPEDAL_UNTIL = "ezvr_backpedal_until";
    private static final String PD_BACKPEDAL_SPEED = "ezvr_backpedal_speed";
    private static final String PD_CIRCLE_UNTIL = "ezvr_circle_until";
    private static final String PD_CIRCLE_SPEED = "ezvr_circle_speed";
    private static final String PD_CIRCLE_DIR = "ezvr_circle_dir";
    private static final String PD_CIRCLE_ZZA = "ezvr_circle_zza";
    private static final String PD_EAT_SLOW_UNTIL = "ezvr_eat_slow_until";

    private static final long SWING_COOLDOWN_TICKS = 30L;
    private static final long SWING_ANIM_TICKS = 6L;
    private static final long NO_HIT_SWING_DELAY_TICKS = 30L;
    private static final long UNREACHABLE_TARGET_COOLDOWN_TICKS = 200L;
    private static final double UNREACHABLE_PROGRESS_DISTANCE = 1.0;
    private static final int BOW_FULL_DRAW_TICKS = 20;
    private static final double RANGED_MIN_RANGE_FLOOR = 4.0;
    private static final double RANGED_MAX_RANGE_PAD = 2.0;
    private static final double RANGED_BACKPEDAL_RANGE_PAD = 0.75;
    private static final float RANGED_INACCURACY = 1.0F;

    private static final double TOO_CLOSE_PAD = 1.5;
    private static final double TOO_CLOSE_HYSTERESIS = 1.6;
    private static final double PEARL_MIN_DISTANCE_SQR = 1.0;

    private static final Map<Villager, State> STATE = new WeakHashMap<>();

    // Reflection caches for yaw/head/body setters (mappings drift)
    private static volatile boolean ROT_REFLECT_SCANNED = false;
    private static volatile Method SET_Y_HEAD_ROT = null;
    private static volatile Method SET_Y_BODY_ROT = null;

    private static volatile Method FINISH_USING_ITEM = null;
    private static volatile boolean FINISH_SCANNED = false;

    private static volatile Method GET_USE_DURATION_0 = null;
    private static volatile Method GET_USE_DURATION_1 = null;
    private static volatile boolean USE_DUR_SCANNED = false;

    private static volatile boolean PROJECTILE_REFLECT_SCANNED = false;
    private static volatile Method PROJECTILE_DRAW = null;
    private static volatile Method PROJECTILE_SHOOT = null;
    private static final Map<Class<?>, Method> PROJECTILE_SHOOT_METHODS = new WeakHashMap<>();

    // -----------------------------------------------------------------------------------------
    // EAT USE-STATE STABILITY (server-side)
    // -----------------------------------------------------------------------------------------

    /**
     * If the eat window is active but something keeps interrupting "using item",
     * repeated startUsingItem() calls will reset the internal use timer to full duration.
     * Many use animations depend on a monotonic countdown / ticks-using progression. :contentReference[oaicite:1]{index=1}
     *
     * So: we repair the internal fields to the expected remaining ticks based on st.eatFinishAt - now.
     */
    private static final boolean DEBUG_EAT_USE_STATE = true;
    private static final long DEBUG_EAT_USE_LOG_INTERVAL_TICKS = 10L;
    private static final long EAT_USE_REPAIR_MIN_INTERVAL_TICKS = 2L;

    // Reflection fallback: patch LivingEntity.useItem + LivingEntity.useItemRemaining on SERVER
    private static volatile boolean USE_FIELDS_SCANNED = false;
    private static volatile Field FIELD_USE_ITEM = null;           // LivingEntity.useItem
    private static volatile Field FIELD_USE_ITEM_REMAINING = null; // LivingEntity.useItemRemaining

    private VillagerCombatDirector() {}

    /**
     * Out-of-combat eating (movement modes IDLE/FOLLOW/PATROL only).
     * Uses the same "consume + heal" pipeline as combat-eating, but without any target logic.
     */
    public static void tickPassiveEat(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!vill.isAlive()) return;

            VillagerBrain.Mode m = VillagerBrain.getMode(vill);
            if (m != VillagerBrain.Mode.IDLE && m != VillagerBrain.Mode.FOLLOW && m != VillagerBrain.Mode.PATROL) return;

            if (VillagerBrain.isUiPaused(vill)) return;

            // If we're actively running combat AI, let combat-eat handle itself.
            // NOTE: combat mode can be set even when no threat is present; that should NOT block passive eating.
            if (VillagerBrain.isCombatEngaged(vill)) return;

            float max = vill.getMaxHealth();
            if (max <= 0.0f) return;
            float frac = vill.getHealth() / max;
            if (frac > PASSIVE_EAT_HP_THRESHOLD) return;

            CombatSettings.AiSettings ai = getAiSettings(vill);
            if (!ai.enableEating) return;

            if (!hasAnyFoodInPickupInv(vill)) return;

            State st = STATE.computeIfAbsent(vill, v -> new State());
            long now = vill.level().getGameTime();

            // Already eating: keep it going until finish.
            if (st.eatFinishAt > 0L) {
                try {
                    // While passively eating in FOLLOW/PATROL, allow normal movement goals to keep moving the villager.
                    if (m == VillagerBrain.Mode.IDLE) vill.getNavigation().stop();
                } catch (Throwable ignored) {}
                ensureStillEating(vill, st, now);

                if (now >= st.eatFinishAt) {
                    finishEat(vill, st);
                    st.eatFinishAt = -1L;
                    st.eatNextFxAt = 0L;
                    st.eatPrevMain = ItemStack.EMPTY;
                    st.eatFoodUsed = ItemStack.EMPTY;

                    st.eatStartAt = -1L;
                    st.eatUseDuration = 0;
                    st.eatLastUseLogAt = 0L;
                    st.eatLastUseRepairAt = 0L;
                }
                return;
            }

            // Start a new passive eat.
            try {
                if (m == VillagerBrain.Mode.IDLE) vill.getNavigation().stop();
            } catch (Throwable ignored) {}
            startEatFromPickupInv(vill, st, now, "passive");

        } catch (Throwable ignored) {}
    }

    public static boolean tickAttack(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;
            if (!target.isAlive()) {
                State st = STATE.get(vill);
                if (st != null) {
                    cancelEatProcess(vill, st, "target_dead");
                    resetRangedState(vill, st, true, "target_dead");
                }
                return false;
            }

            State st = STATE.computeIfAbsent(vill, v -> new State());
            if (st.targetId != null && !st.targetId.equals(target.getUUID())) {
                st.hitSinceLastSwing = false;
                st.blockNoHitSince = -1L;
                st.lastHurtTimeSeen = 0;
                st.lastHitAt = -1L;
                resetRangedState(vill, st, false, "target_switch");
                st.eatPhase = EatPhase.NONE;
                st.eatResetCount = 0;
                st.eatHitSeenAt = -1L;
                st.eatBackpedalHitCount = 0;
                st.eatBlockedSeenAt = -1L;

                // eat use stability tracking
                st.eatStartAt = -1L;
                st.eatUseDuration = 0;
                st.eatLastUseLogAt = 0L;
                st.eatLastUseRepairAt = 0L;

                resetUnreachableState(st);
            }
            st.targetId = target.getUUID();

            long now = vill.level().getGameTime();
            resumeAfterUiPause(st, now);

            if (VillagerBrain.isUiPaused(vill)) {
                suspendForUi(vill);
                return true;
            }

            if (!VillagerBrain.shouldCombatActNow(vill)) {
                stop(vill);
                return false;
            }

            enterCombatIfNeeded(vill, st);

            detectHitEdge(vill, st, now);

            CombatSettings.AiSettings ai = getAiSettings(vill);

            // Low HP: prioritize escape-to-eat loop while we have food available.
            if (ai.enableEating && shouldTryEatInCombat(vill) && hasAnyFoodInPickupInv(vill)) {
                if (tickEatEscapeProcess(vill, target, st, now)) return true;
            } else {
                // If we recovered above threshold or have no food, ensure we don't keep stale state.
                if (st.eatPhase != EatPhase.NONE) cancelEatProcess(vill, st, "eat_abort_nofood_or_recovered");
            }

            RangedLoadout ranged = resolveRangedLoadout(vill);
            if (ranged != null && ranged.hasAmmo) {
                if (shouldAbortUnreachableCombat(vill, target, st, now, ranged, ai)) {
                    markTimedOutTarget(vill, st, target, now, ai);
                    finishCombatAndResume(vill, "combat_unreachable_timeout");
                    return false;
                }
                if (tickRangedAttack(vill, target, st, now, ai, ranged)) return true;
            } else {
                resetRangedState(vill, st, false, (ranged == null ? "not_projectile_weapon" : "missing_offhand_ammo"));
            }

            double reach = computeReach(vill, target);
            double swingRange = reach + SAFETY_MARGIN;
            double maintainDist = computeMaintainDistance(reach);

            double dist = vill.distanceTo(target);
            boolean inSwingRange = dist <= swingRange;

            if (shouldAbortUnreachableCombat(vill, target, st, now, null, ai)) {
                markTimedOutTarget(vill, st, target, now, ai);
                finishCombatAndResume(vill, "combat_unreachable_timeout");
                return false;
            }

            if (!inSwingRange) {
                try { vill.getNavigation().moveTo(target, MOVE_SPEED); } catch (Throwable ignored) {}
            } else {
                // Maintain a safer spacing (avoid "kissing" range) and circle the target while fighting.
                if (dist < (maintainDist - 0.15)) {
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    faceTargetHard(vill, target);

                    if (canBackpedalBehind(vill, target)) {
                        applyBackpedalInput(vill, now, 0.50f);
                    } else {
                        if (ai.enableCircling) {
                            applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                        }
                    }
                } else if (dist > (maintainDist + 0.65)) {
                    try { vill.getNavigation().moveTo(target, MOVE_SPEED); } catch (Throwable ignored) {}
                } else {
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    faceTargetHard(vill, target);
                    if (ai.enableCircling) {
                        applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                    }
                }
            }

            if (now < st.noBlockUntil) {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            }

            boolean canSwingNow = inSwingRange && now >= st.noBlockUntil && now >= st.nextSwingAt;

            boolean wantSwing = false;
            String swingReason = null;

            if (st.hitSinceLastSwing) {
                wantSwing = true;
                swingReason = "hit";
            } else {
                if (st.blockNoHitSince < 0L) {
                    st.blockNoHitSince = now;
                } else {
                    long noHitFor = now - st.blockNoHitSince;
                    if (noHitFor >= NO_HIT_SWING_DELAY_TICKS) {
                        wantSwing = true;
                        swingReason = "no_hit_timeout";
                    }
                }
            }

            if (wantSwing && canSwingNow) {
                performSwing(vill, target, st, now, swingReason);
                faceTargetHard(vill, target);
                lockYaw(vill, now, vill.getYRot());
                return true;
            }

            boolean allowBlocking = ENABLE_BLOCKING && ai.enableBlocking;
            if (allowBlocking) {
                if (now >= st.noBlockUntil) {
                    boolean started = startBlocking(vill);
                    if (started) {
                        if (st.blockNoHitSince < 0L) st.blockNoHitSince = now;
                    }
                } else {
                    try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                }
            } else {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            }

            // Head look
            try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}

            // Body facing
            faceTargetHard(vill, target);
            lockYaw(vill, now, vill.getYRot());

            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDirector.tickAttack failed (soft): {}", t.toString());
            return false;
        }
    }

    public static void stop(Villager vill) {
        try {
            if (vill == null) return;
            State st = STATE.get(vill);
            if (st != null) {
                cancelEatProcess(vill, st, "stop");
                resetRangedState(vill, st, true, "stop");
                st.targetId = null;
                st.combatStartPos = null;
                st.uiPausedAt = -1L;
                resetUnreachableState(st);
            }
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            try {
                CompoundTag pd = vill.getPersistentData();
                pd.remove(PD_LOCK_YAW_UNTIL);
                pd.remove(PD_LOCK_YAW);
                pd.remove(PD_BACKPEDAL_UNTIL);
                pd.remove(PD_BACKPEDAL_SPEED);
                pd.remove(PD_CIRCLE_UNTIL);
                pd.remove(PD_CIRCLE_SPEED);
                pd.remove(PD_CIRCLE_DIR);
                pd.remove(PD_CIRCLE_ZZA);
            } catch (Throwable ignored) {}
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
    }

    public static void suspendForUi(Villager vill) {
        try {
            if (vill == null || vill.level() == null || vill.level().isClientSide()) return;
            State st = STATE.get(vill);
            if (st != null && st.uiPausedAt < 0L) {
                st.uiPausedAt = vill.level().getGameTime();
            }
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void resumeAfterUiPause(State st, long now) {
        try {
            if (st == null || st.uiPausedAt < 0L || now <= st.uiPausedAt) {
                if (st != null && st.uiPausedAt >= 0L && now <= st.uiPausedAt) st.uiPausedAt = -1L;
                return;
            }
            long delta = now - st.uiPausedAt;
            st.uiPausedAt = -1L;
            shiftTimestamp(st.nextSwingAt, delta, v -> st.nextSwingAt = v);
            shiftTimestamp(st.lastSwingAt, delta, v -> st.lastSwingAt = v);
            shiftTimestamp(st.noBlockUntil, delta, v -> st.noBlockUntil = v);
            shiftTimestamp(st.lastHitAt, delta, v -> st.lastHitAt = v);
            shiftTimestamp(st.blockNoHitSince, delta, v -> st.blockNoHitSince = v);
            shiftTimestamp(st.eatHitSeenAt, delta, v -> st.eatHitSeenAt = v);
            shiftTimestamp(st.eatFinishAt, delta, v -> st.eatFinishAt = v);
            shiftTimestamp(st.eatNextFxAt, delta, v -> st.eatNextFxAt = v);
            shiftTimestamp(st.eatStartAt, delta, v -> st.eatStartAt = v);
            shiftTimestamp(st.eatLastUseRepairAt, delta, v -> st.eatLastUseRepairAt = v);
            shiftTimestamp(st.unreachableSince, delta, v -> st.unreachableSince = v);
            shiftTimestamp(st.lastProgressAt, delta, v -> st.lastProgressAt = v);
            shiftTimestamp(st.rangedChargeStartAt, delta, v -> st.rangedChargeStartAt = v);
        } catch (Throwable ignored) {}
    }

    private interface LongSetter {
        void set(long value);
    }

    private static void shiftTimestamp(long value, long delta, LongSetter setter) {
        try {
            if (setter == null || value < 0L || delta <= 0L) return;
            setter.set(value + delta);
        } catch (Throwable ignored) {}
    }

    public static void finishCombatAndResume(Villager vill, String reason) {
        try {
            if (vill == null) return;
            boolean wasEngaged = VillagerBrain.isCombatEngaged(vill);
            Vec3 resumeDest = null;
            if (wasEngaged && vill.level() instanceof ServerLevel level) {
                State st = STATE.get(vill);
                resumeDest = resolveResumeDestination(vill, level, st);
            }
            stop(vill);
            if (!wasEngaged) return;
            tryUseResumeEnderPearl(vill, resumeDest, reason);
        } catch (Throwable ignored) {}
    }

    public static boolean isTargetOnUnreachableCooldown(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null || vill.level() == null) return false;
            State st = STATE.get(vill);
            if (st == null) return false;
            long now = vill.level().getGameTime();
            if (st.lastTimedOutTargetUntil <= now) return false;
            return target.getUUID().equals(st.lastTimedOutTargetId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clearTargetUnreachableCooldown(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return;
            State st = STATE.get(vill);
            if (st == null) return;
            if (target.getUUID().equals(st.lastTimedOutTargetId)) {
                st.lastTimedOutTargetId = null;
                st.lastTimedOutTargetUntil = 0L;
            }
        } catch (Throwable ignored) {}
    }

    private static boolean tickEatEscapeProcess(Villager vill, LivingEntity target, State st, long now) {
        try {
            if (vill == null || target == null || st == null) return false;

            CombatSettings.AiSettings ai = getAiSettings(vill);
            int maxResets = clampInt(ai.eatMaxResets, 0, 5);
            int forceHits = clampInt(ai.eatForceHits, 0, 6);
            boolean allowBlocking = ENABLE_BLOCKING && ai.enableBlocking;
            boolean allowCircling = ai.enableCircling;

            // Initialize.
            if (st.eatPhase == EatPhase.NONE) {
                st.eatPhase = EatPhase.BACKPEDAL;
                st.eatResetCount = 0;
                st.eatHitSeenAt = st.lastHitAt;
                st.eatFinishAt = -1L;
                st.eatPrevMain = ItemStack.EMPTY;
                st.eatFoodUsed = ItemStack.EMPTY;
                st.eatBackpedalHitCount = 0;
                st.eatBlockedSeenAt = -1L;

                // eat use stability tracking
                st.eatStartAt = -1L;
                st.eatUseDuration = 0;
                st.eatLastUseLogAt = 0L;
                st.eatLastUseRepairAt = 0L;

                if (forceHits <= 0) {
                    st.eatPhase = EatPhase.BACKPEDAL_EAT;
                }
            }

            boolean gotHit = st.lastHitAt >= 0L && st.lastHitAt > st.eatHitSeenAt;
            if (gotHit) {
                st.eatHitSeenAt = st.lastHitAt;

                // While we are still in the "backpedal" stage, do NOT reset the entire process.
                // We keep fighting while backing up, and after a few hits we eat anyway.
                if (st.eatPhase == EatPhase.BACKPEDAL) {
                    st.eatBackpedalHitCount++;
                    if (st.eatBackpedalHitCount >= forceHits) {
                        st.eatPhase = EatPhase.BACKPEDAL_EAT;
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] villager={} action=backpedal_force_eat hits={}",
                                vill.getUUID(), st.eatBackpedalHitCount);
                    }
                } else if (st.eatPhase != EatPhase.FORCE_EAT && st.eatPhase != EatPhase.BACKPEDAL_EAT) {
                    st.eatResetCount++;
                    cancelEatInProgressOnly(vill, st, "eat_reset_hit");
                    if (st.eatResetCount >= maxResets) {
                        st.eatPhase = EatPhase.FORCE_EAT;
                    } else {
                        st.eatPhase = EatPhase.BACKPEDAL;
                    }
                }
            }

            // Also count fully blocked hits: our hurt() is cancelled when blocked, so hurtTime doesn't tick.
            if (st.eatPhase == EatPhase.BACKPEDAL) {
                try {
                    long blockedTick = vill.getPersistentData().getLong(PD_BLOCKED_TICK);
                    if (blockedTick > 0L && blockedTick != st.eatBlockedSeenAt) {
                        st.eatBlockedSeenAt = blockedTick;
                        st.eatBackpedalHitCount++;
                        if (st.eatBackpedalHitCount >= forceHits) {
                            st.eatPhase = EatPhase.BACKPEDAL_EAT;
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] villager={} action=backpedal_force_eat why=blocked hits={}",
                                    vill.getUUID(), st.eatBackpedalHitCount);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            double dist = vill.distanceTo(target);

            switch (st.eatPhase) {
                case BACKPEDAL -> {
                    // Backpedal slowly while shielding and facing target.
                    if (allowBlocking) startBlocking(vill);
                    else {
                        try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                    }
                    faceTargetHard(vill, target);
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    if (canBackpedalBehind(vill, target)) {
                        applyBackpedalInput(vill, now, (float) EAT_BACKPEDAL_SPEED);
                    } else {
                        if (allowCircling) {
                            applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                        }
                    }

                    // Keep normal combat (swing/block) logic while backpedaling so we don't get trapped in 1v1s.
                    double reach = computeReach(vill, target);
                    double swingRange = reach + SAFETY_MARGIN;
                    boolean inSwingRange = dist <= swingRange;

                    if (now < st.noBlockUntil) {
                        try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                    }

                    boolean canSwingNow = inSwingRange && now >= st.noBlockUntil && now >= st.nextSwingAt;

                    boolean wantSwing = false;
                    String swingReason = null;

                    if (st.hitSinceLastSwing) {
                        wantSwing = true;
                        swingReason = "hit";
                    } else {
                        if (st.blockNoHitSince < 0L) {
                            st.blockNoHitSince = now;
                        } else {
                            long noHitFor = now - st.blockNoHitSince;
                            if (noHitFor >= NO_HIT_SWING_DELAY_TICKS) {
                                wantSwing = true;
                                swingReason = "no_hit_timeout";
                            }
                        }
                    }

                    if (wantSwing && canSwingNow) {
                        performSwing(vill, target, st, now, swingReason);
                        faceTargetHard(vill, target);
                    }

                    if (allowBlocking) {
                        if (now >= st.noBlockUntil) {
                            boolean started = startBlocking(vill);
                            if (started) {
                                if (st.blockNoHitSince < 0L) st.blockNoHitSince = now;
                            }
                        } else {
                            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                        }
                    } else {
                        try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                    }

                    try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}
                    faceTargetHard(vill, target);
                    lockYaw(vill, now, vill.getYRot());

                    if (dist >= EAT_DIST_RUN_START) {
                        st.eatPhase = EatPhase.RUN;
                    }
                }
                case BACKPEDAL_EAT -> {
                    // Last resort: stop blocking and eat while continuing to backpedal.
                    faceTargetHard(vill, target);
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    if (canBackpedalBehind(vill, target)) {
                        applyBackpedalInput(vill, now, (float) EAT_BACKPEDAL_FORCE_EAT_SPEED);
                    } else {
                        // Strafe to find a clearer backpedal line, but keep eating.
                        if (allowCircling) {
                            applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), -0.2f);
                        }
                    }
                    try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}
                    lockYaw(vill, now, vill.getYRot());

                    if (st.eatFinishAt <= 0L) {
                        // Ensure we are not blocking when starting to eat.
                        try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                        if (!startEatFromPickupInv(vill, st, now, "backpedal_force")) {
                            cancelEatProcess(vill, st, "eat_start_failed");
                            return false;
                        }
                    } else {
                        ensureStillEating(vill, st, now);
                    }

                    if (now >= st.eatFinishAt) {
                        finishEat(vill, st);

                        st.eatPhase = EatPhase.NONE;
                        st.eatFinishAt = -1L;
                        st.eatPrevMain = ItemStack.EMPTY;
                        st.eatFoodUsed = ItemStack.EMPTY;
                        st.eatResetCount = 0;
                        st.eatBackpedalHitCount = 0;
                        st.eatBlockedSeenAt = -1L;

                        // eat use stability tracking
                        st.eatStartAt = -1L;
                        st.eatUseDuration = 0;
                        st.eatLastUseLogAt = 0L;
                        st.eatLastUseRepairAt = 0L;
                    }
                }
                case RUN -> {
                    // Turn around and walk away to widen the gap.
                    try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                    faceAwayFromTargetHard(vill, target);
                    runAwayFrom(vill, target, EAT_RUN_SPEED, 7.0);

                    if (dist > EAT_DIST_START_EATING) {
                        st.eatPhase = EatPhase.EAT;
                    }
                }
                case EAT, FORCE_EAT -> {
                    // Face enemy and eat. FORCE_EAT ignores hit resets.
                    faceTargetHard(vill, target);
                    try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}
                    lockYaw(vill, now, vill.getYRot());

                    // While eating, allow movement but at a slow rate (0.25x base speed).
                    try {
                        double reach = computeReach(vill, target);
                        double maintainDist = computeMaintainDistance(reach);
                        if (dist < (maintainDist - 0.15)) {
                            if (canBackpedalBehind(vill, target)) {
                                applyBackpedalInput(vill, now, 0.50f);
                            } else {
                                applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                            }
                        } else {
                            applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                        }
                    } catch (Throwable ignored) {}

                    if (st.eatFinishAt <= 0L) {
                        if (!startEatFromPickupInv(vill, st, now, st.eatPhase == EatPhase.FORCE_EAT ? "force" : "normal")) {
                            // No food after all; fall back to combat.
                            cancelEatProcess(vill, st, "eat_start_failed");
                            return false;
                        }
                    } else {
                        ensureStillEating(vill, st, now);
                    }

                    if (now >= st.eatFinishAt) {
                        finishEat(vill, st);

                        // If we are still below threshold, start another loop next tick.
                        st.eatPhase = EatPhase.NONE;
                        st.eatFinishAt = -1L;
                        st.eatPrevMain = ItemStack.EMPTY;
                        st.eatFoodUsed = ItemStack.EMPTY;
                        st.eatResetCount = 0;
                        st.eatBackpedalHitCount = 0;
                        st.eatBlockedSeenAt = -1L;

                        // eat use stability tracking
                        st.eatStartAt = -1L;
                        st.eatUseDuration = 0;
                        st.eatLastUseLogAt = 0L;
                        st.eatLastUseRepairAt = 0L;

                    }
                }
                default -> {}
            }

            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDirector.tickEatEscapeProcess failed (soft): {}", t.toString());
            return false;
        }
    }

    public static boolean tryUseResumeEnderPearl(Villager vill, Vec3 dest, String reason) {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel level)) return false;
            if (!hasResumeEnderPearl(vill)) return false;

            if (dest == null) return false;
            if (!sameDimensionTarget(vill, level, dest)) return false;
            if (vill.position().distanceToSqr(dest) < PEARL_MIN_DISTANCE_SQR) return false;

            playPearlFx(level, vill.position());
            vill.teleportTo(dest.x, dest.y, dest.z);
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            playPearlFx(level, dest);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_resume_pearl] villager={} mode={} reason={} dest=({}, {}, {})",
                    safeUuid(vill),
                    VillagerBrain.getMode(vill).id,
                    safe(reason),
                    trim1(dest.x), trim1(dest.y), trim1(dest.z));
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_resume_pearl] failed (soft): {}", t.toString());
        }
        return false;
    }

    public static boolean hasResumeEnderPearl(Villager vill) {
        try {
            if (vill == null) return false;
            Container pickup = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
            if (containerHasItem(pickup, Items.ENDER_PEARL)) return true;
            return containerHasItem(vill.getInventory(), Items.ENDER_PEARL);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containerHasItem(Container inv, net.minecraft.world.item.Item item) {
        try {
            if (inv == null || item == null) return false;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                if (st.getItem() == item && st.getCount() > 0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Vec3 resolveResumeDestination(Villager vill, ServerLevel level, State st) {
        try {
            if (vill == null || level == null) return null;

            if (st != null && st.combatStartPos != null && sameDimensionTarget(vill, level, st.combatStartPos)) {
                return st.combatStartPos;
            }

            if (VillagerBrain.isHelpReturnActive(vill)) {
                Vec3 helpPos = VillagerBrain.getHelpReturnPos(vill);
                if (helpPos != null) return centerOf(helpPos);
            }

            if (VillagerBrain.isManualFarmingControlling(vill)) {
                FarmingSettingsService.RegisteredWorkstation ws = FarmingSettingsService.getEffectiveWorkstation(level, vill);
                if (ws != null) {
                    String dim = "";
                    try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) {}
                    if (ws.dimId() != null && ws.dimId().equals(dim)) {
                        return new Vec3(ws.x() + 0.5, ws.y() + 0.5, ws.z() + 0.5);
                    }
                }
            }

            VillagerBrain.Mode mode = VillagerBrain.getMode(vill);
            if (mode == VillagerBrain.Mode.PATROL) {
                List<Vec3> waypoints = VillagerBrain.getPatrolWaypoints(vill);
                if (waypoints != null && !waypoints.isEmpty()) {
                    int idx = VillagerBrain.getPatrolIndex(vill);
                    if (idx < 0) idx = 0;
                    if (idx >= waypoints.size()) idx = waypoints.size() - 1;
                    Vec3 wp = waypoints.get(idx);
                    if (wp != null) return centerOf(wp);
                }
            }

            if (mode == VillagerBrain.Mode.FOLLOW) {
                java.util.UUID follow = VillagerBrain.getFollowPlayer(vill);
                if (follow != null) {
                    var sp = level.getServer().getPlayerList().getPlayer(follow);
                    if (sp != null && sp.isAlive()) {
                        return new Vec3(sp.getX(), sp.getY(), sp.getZ());
                    }
                }
            }

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameDimensionTarget(Villager vill, ServerLevel level, Vec3 dest) {
        try {
            if (vill == null || level == null || dest == null) return false;
            if (VillagerBrain.isHelpReturnActive(vill)) {
                String dim = VillagerBrain.getHelpReturnDim(vill);
                String cur = "";
                try { cur = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { cur = ""; }
                if (dim != null && !dim.isBlank() && !dim.equals(cur)) return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static Vec3 centerOf(Vec3 pos) {
        if (pos == null) return null;
        return new Vec3(pos.x, pos.y, pos.z);
    }

    private static void playPearlFx(ServerLevel level, Vec3 pos) {
        try {
            if (level == null || pos == null) return;
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.8f, 1.0f);
        } catch (Throwable ignored) {}
        try {
            if (level == null || pos == null) return;
            level.sendParticles(ParticleTypes.PORTAL, pos.x, pos.y + 0.9, pos.z, 24, 0.35, 0.45, 0.35, 0.08);
        } catch (Throwable ignored) {}
    }

    private static boolean tickRangedAttack(Villager vill, LivingEntity target, State st, long now, CombatSettings.AiSettings ai, RangedLoadout loadout) {
        try {
            if (vill == null || target == null || st == null || loadout == null) return false;
            if (!(vill.level() instanceof ServerLevel serverLevel)) return false;
            if (!loadout.hasAmmo) return false;

            ItemStack main = loadout.weaponStack;
            if (main == null || main.isEmpty()) {
                resetRangedState(vill, st, true, "empty_main");
                return false;
            }

            if (vill.isUsingItem()) {
                try {
                    if (vill.getUsedItemHand() != InteractionHand.MAIN_HAND) {
                        vill.stopUsingItem();
                    }
                } catch (Throwable ignored) {}
            }

            boolean hasLos = hasLineOfSightSafe(vill, target);
            double dist = vill.distanceTo(target);
            double preferredRange = computePreferredRangedDistance(loadout);
            double minRange = Math.max(RANGED_MIN_RANGE_FLOOR, preferredRange - RANGED_BACKPEDAL_RANGE_PAD);
            double maxRange = Math.max(minRange + 2.0, loadout.weaponItem.getDefaultProjectileRange() + RANGED_MAX_RANGE_PAD);

            try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}
            faceTargetHard(vill, target);
            lockYaw(vill, now, vill.getYRot());

            if (dist < minRange) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                if (canBackpedalBehind(vill, target)) {
                    applyBackpedalInput(vill, now, 0.50f);
                } else {
                    backpedalAway(vill, target, MOVE_SPEED, 2.5);
                    if (ai.enableCircling) {
                        applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                    }
                }
            } else if (dist > maxRange || !hasLos) {
                try { vill.getNavigation().moveTo(target, MOVE_SPEED); } catch (Throwable ignored) {}
            } else {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                if (ai.enableCircling) {
                    applyCircleInput(vill, now, CIRCLE_SPEED, pickCircleDir(vill, st, now), 0.0f);
                }
            }

            if (loadout.crossbowLike && CrossbowItem.isCharged(main)) {
                if (hasLos && dist <= maxRange && now >= st.nextSwingAt) {
                    fireCrossbowShot(serverLevel, vill, target, st, now, loadout, main);
                }
                return true;
            }

            if (!hasLos || dist > maxRange || now < st.nextSwingAt) {
                return true;
            }

            if (!vill.isUsingItem()) {
                try { vill.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
                st.rangedCharging = true;
                st.rangedChargeStartAt = now;
                return true;
            }

            if (!st.rangedCharging) {
                st.rangedCharging = true;
                st.rangedChargeStartAt = now;
            }
            if (st.rangedChargeStartAt < 0L) st.rangedChargeStartAt = now;

            int chargeTicks = (int) Math.max(0L, now - st.rangedChargeStartAt + 1L);
            if (loadout.crossbowLike) {
                int chargeNeeded = Math.max(1, CrossbowItem.getChargeDuration(main, vill));
                if (chargeTicks >= chargeNeeded) {
                    List<ItemStack> projectiles = drawProjectiles(main, loadout.ammoStack, vill);
                    if (!projectiles.isEmpty()) {
                        main.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(projectiles));
                        try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                        st.rangedCharging = false;
                        st.rangedChargeStartAt = -1L;
                        if (now >= st.nextSwingAt) {
                            fireCrossbowShot(serverLevel, vill, target, st, now, loadout, main);
                        }
                    } else {
                        resetRangedState(vill, st, true, "crossbow_draw_failed");
                    }
                }
            } else {
                if (chargeTicks >= BOW_FULL_DRAW_TICKS) {
                    float power = BowItem.getPowerForTime(chargeTicks);
                    if (power >= 0.1F) {
                        fireBowLikeShot(serverLevel, vill, target, st, now, loadout, main, power);
                    }
                }
            }

            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tickRangedAttack failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void fireBowLikeShot(ServerLevel level, Villager vill, LivingEntity target, State st, long now, RangedLoadout loadout, ItemStack main, float power) {
        try {
            List<ItemStack> projectiles = drawProjectiles(main, loadout.ammoStack, vill);
            if (projectiles.isEmpty()) {
                resetRangedState(vill, st, true, "bow_draw_failed");
                return;
            }

            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            try { vill.swing(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}

            if (!invokeProjectileShoot(loadout.weaponItem, level, vill, InteractionHand.MAIN_HAND, main, projectiles, power * 3.0F, RANGED_INACCURACY, power >= 1.0F, target)) {
                resetRangedState(vill, st, true, "bow_shoot_failed");
                return;
            }

            finishRangedShot(vill, st, now, main, "bow");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] fireBowLikeShot failed (soft): {}", t.toString());
        }
    }

    private static void fireCrossbowShot(ServerLevel level, Villager vill, LivingEntity target, State st, long now, RangedLoadout loadout, ItemStack main) {
        try {
            if (!(loadout.weaponItem instanceof CrossbowItem crossbow)) return;
            if (!CrossbowItem.isCharged(main)) return;

            try { vill.swing(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
            crossbow.performShooting(level, vill, InteractionHand.MAIN_HAND, main, computeCrossbowVelocity(main), RANGED_INACCURACY, target);
            finishRangedShot(vill, st, now, main, "crossbow");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] fireCrossbowShot failed (soft): {}", t.toString());
        }
    }

    private static void finishRangedShot(Villager vill, State st, long now, ItemStack main, String mode) {
        try {
            st.nextSwingAt = now + SWING_COOLDOWN_TICKS;
            st.lastSwingAt = now;
            st.noBlockUntil = now + Math.max(1L, SWING_ANIM_TICKS);
            st.rangedCharging = false;
            st.rangedChargeStartAt = -1L;

            syncMainLoadoutAfterRangedUse(vill, main, mode);

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] RANGED_SHOT mode={} villager={} mainItem={}",
                    safe(mode),
                    safeUuid(vill),
                    safeItemId(main)
            );
        } catch (Throwable ignored) {}
    }

    private static void syncMainLoadoutAfterRangedUse(Villager vill, ItemStack main, String reason) {
        try {
            if (vill == null) return;
            if (main == null || main.isEmpty()) {
                VillagerCombatLoadoutService.clearDesiredMainIfPresent(vill, "ranged_" + safe(reason) + "_broke");
            } else {
                VillagerCombatLoadoutService.updateDesiredMainFromHandIfPresent(vill, main, "ranged_" + safe(reason));
            }
        } catch (Throwable ignored) {}
    }

    private static void resetRangedState(Villager vill, State st, boolean stopUsing, String reason) {
        try {
            if (st == null) return;
            st.rangedCharging = false;
            st.rangedChargeStartAt = -1L;
            if (stopUsing && vill != null) {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static RangedLoadout resolveRangedLoadout(Villager vill) {
        try {
            if (vill == null) return null;
            ItemStack main = vill.getMainHandItem();
            if (main == null || main.isEmpty()) return null;
            if (!(main.getItem() instanceof ProjectileWeaponItem weapon)) return null;

            ItemStack off = vill.getOffhandItem();
            boolean hasAmmo = isValidHeldProjectile(weapon, main, off);
            return new RangedLoadout(main, off == null ? ItemStack.EMPTY : off, weapon, isCrossbowLike(main, weapon), hasAmmo);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isValidHeldProjectile(ProjectileWeaponItem weapon, ItemStack weaponStack, ItemStack heldStack) {
        try {
            if (weapon == null || weaponStack == null || weaponStack.isEmpty()) return false;
            if (heldStack == null || heldStack.isEmpty()) return false;
            return weapon.getSupportedHeldProjectiles(weaponStack).test(heldStack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCrossbowLike(ItemStack weaponStack, ProjectileWeaponItem weapon) {
        try {
            if (weapon instanceof CrossbowItem) return true;
            if (weaponStack != null && !weaponStack.isEmpty()) {
                try {
                    if (weaponStack.getUseAnimation() == UseAnim.CROSSBOW) return true;
                } catch (Throwable ignored) {}
                try {
                    if (weaponStack.useOnRelease()) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static double computePreferredRangedDistance(RangedLoadout loadout) {
        try {
            if (loadout == null || loadout.weaponItem == null) return 6.0;
            double base = loadout.weaponItem.getDefaultProjectileRange();
            if (base <= 0.0) base = 8.0;
            return Mth.clamp(base * 0.65, 5.0, 12.0);
        } catch (Throwable ignored) {
            return 6.0;
        }
    }

    private static boolean hasLineOfSightSafe(Villager vill, LivingEntity target) {
        try {
            return vill != null && target != null && vill.hasLineOfSight(target);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static float computeCrossbowVelocity(ItemStack main) {
        try {
            ChargedProjectiles charged = main.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
            return charged.contains(Items.FIREWORK_ROCKET) ? 1.6F : 3.15F;
        } catch (Throwable ignored) {
            return 3.15F;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> drawProjectiles(ItemStack weapon, ItemStack ammo, LivingEntity shooter) {
        try {
            warmupProjectileReflection();
            if (PROJECTILE_DRAW == null) return List.of();
            if (weapon == null || weapon.isEmpty() || ammo == null || ammo.isEmpty() || shooter == null) return List.of();

            ItemStack ammoCopy = ammo.copy();
            Object out = PROJECTILE_DRAW.invoke(null, weapon, ammoCopy, shooter);
            if (out instanceof List<?> raw) {
                return (List<ItemStack>) raw;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] drawProjectiles reflection failed (soft): {}", t.toString());
        }
        return List.of();
    }

    private static boolean invokeProjectileShoot(
            ProjectileWeaponItem weapon,
            ServerLevel level,
            LivingEntity shooter,
            InteractionHand hand,
            ItemStack weaponStack,
            List<ItemStack> projectileItems,
            float velocity,
            float inaccuracy,
            boolean crit,
            LivingEntity target
    ) {
        try {
            warmupProjectileReflection();
            if (weapon == null || level == null || shooter == null || hand == null || weaponStack == null || weaponStack.isEmpty()) return false;
            if (projectileItems == null || projectileItems.isEmpty()) return false;

            Method shoot = resolveProjectileShootMethod(weapon.getClass());
            if (shoot == null) shoot = PROJECTILE_SHOOT;
            if (shoot == null) return false;

            shoot.invoke(weapon, level, shooter, hand, weaponStack, projectileItems, velocity, inaccuracy, crit, target);
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] invokeProjectileShoot reflection failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void warmupProjectileReflection() {
        if (PROJECTILE_REFLECT_SCANNED) return;
        PROJECTILE_REFLECT_SCANNED = true;
        try {
            PROJECTILE_DRAW = ProjectileWeaponItem.class.getDeclaredMethod("draw", ItemStack.class, ItemStack.class, LivingEntity.class);
            PROJECTILE_DRAW.setAccessible(true);
        } catch (Throwable ignored) {}

        try {
            Class<?> cls = ProjectileWeaponItem.class;
            while (cls != null && PROJECTILE_SHOOT == null) {
                try {
                    Method m = cls.getDeclaredMethod(
                            "shoot",
                            ServerLevel.class,
                            LivingEntity.class,
                            InteractionHand.class,
                            ItemStack.class,
                            List.class,
                            float.class,
                            float.class,
                            boolean.class,
                            LivingEntity.class
                    );
                    m.setAccessible(true);
                    PROJECTILE_SHOOT = m;
                    break;
                } catch (Throwable ignored) {}
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private static Method resolveProjectileShootMethod(Class<?> weaponClass) {
        try {
            if (weaponClass == null) return PROJECTILE_SHOOT;
            synchronized (PROJECTILE_SHOOT_METHODS) {
                Method cached = PROJECTILE_SHOOT_METHODS.get(weaponClass);
                if (cached != null) return cached;
            }

            Class<?> cls = weaponClass;
            while (cls != null && ProjectileWeaponItem.class.isAssignableFrom(cls)) {
                try {
                    Method m = cls.getDeclaredMethod(
                            "shoot",
                            ServerLevel.class,
                            LivingEntity.class,
                            InteractionHand.class,
                            ItemStack.class,
                            List.class,
                            float.class,
                            float.class,
                            boolean.class,
                            LivingEntity.class
                    );
                    m.setAccessible(true);
                    synchronized (PROJECTILE_SHOOT_METHODS) {
                        PROJECTILE_SHOOT_METHODS.put(weaponClass, m);
                    }
                    return m;
                } catch (Throwable ignored) {}
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return PROJECTILE_SHOOT;
    }

    private static void performSwing(Villager vill, LivingEntity target, State st, long now, String reason) {
        try {
            if (vill == null || target == null || st == null) return;

            try { vill.stopUsingItem(); } catch (Throwable ignored) {}

            doSwingAndHit(vill, target, st, now);

            st.noBlockUntil = now + Math.max(1L, SWING_ANIM_TICKS);

            st.hitSinceLastSwing = false;
            st.blockNoHitSince = st.noBlockUntil;

            if (st.lastSwingReasonLogAt <= 0L || (now - st.lastSwingReasonLogAt) >= 10L) {
                st.lastSwingReasonLogAt = now;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat SWING reason={} villager={} target={}",
                        (reason == null ? "unknown" : reason),
                        vill.getUUID(),
                        target.getUUID());
            }

        } catch (Throwable ignored) {}
    }

    private static void doSwingAndHit(Villager vill, LivingEntity target, State st, long now) {
        try {
            if (vill == null || target == null || st == null) return;

            double atkBase = -1.0;
            double atkVal = -1.0;
            try {
                AttributeInstance inst = vill.getAttribute(Attributes.ATTACK_DAMAGE);
                if (inst != null) {
                    atkBase = inst.getBaseValue();
                    atkVal = inst.getValue();
                }
            } catch (Throwable ignored) {}

            ItemStack main = ItemStack.EMPTY;
            try { main = vill.getMainHandItem(); } catch (Throwable ignored) {}

            float beforeHp = -1.0f;
            try { beforeHp = target.getHealth(); } catch (Throwable ignored) {}

            try { vill.swing(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}

            try {
                if (vill instanceof VillagerOverhaulSwingAccess acc) {
                    int prev = acc.ezvr$getSwingSeq();
                    acc.ezvr$setSwingSeq(prev + 1);
                }
            } catch (Throwable ignored) {}

            boolean hit = false;
            try {
                hit = vill.doHurtTarget(target);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] doHurtTarget threw (villager={} target={} err={})",
                        safeUuid(vill), safeUuid(target), t.toString());
                hit = false;
            }

            float afterHp = -1.0f;
            try { afterHp = target.getHealth(); } catch (Throwable ignored) {}

            st.nextSwingAt = now + SWING_COOLDOWN_TICKS;
            st.lastSwingAt = now;

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] SWING (villager={} target={} hit={} hp {}->{} atkBase={} atkVal={} mainItem={})",
                    safeUuid(vill),
                    safeUuid(target),
                    hit,
                    trim1(beforeHp),
                    trim1(afterHp),
                    trim3(atkBase),
                    trim3(atkVal),
                    safeItemId(main)
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] doSwingAndHit failed (soft): {}", t.toString());
        }
    }

    private static void detectHitEdge(Villager vill, State st, long now) {
        try {
            if (vill == null || st == null) return;

            int ht = 0;
            try { ht = vill.hurtTime; } catch (Throwable ignored) { ht = 0; }

            if (ht > st.lastHurtTimeSeen) {
                st.lastHitAt = now;
                st.hitSinceLastSwing = true;
                st.blockNoHitSince = now;

                if (st.lastHitLogAt <= 0L || (now - st.lastHitLogAt) >= 10L) {
                    st.lastHitLogAt = now;
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat HIT detected (villager={} hurtTime={} now={})",
                            vill.getUUID(), ht, now);
                }
            }

            // Our manual shield block cancels hurt() entirely, so hurtTime will not tick up.
            // Treat a "blocked this tick" marker as an incoming attack so we still swing in multi-attacker scenarios.
            try {
                long blockedTick = vill.getPersistentData().getLong(PD_BLOCKED_TICK);
                if (blockedTick > 0L && blockedTick > st.lastBlockedTickSeen) {
                    st.lastBlockedTickSeen = blockedTick;
                    st.lastHitAt = now;
                    st.hitSinceLastSwing = true;
                    st.blockNoHitSince = now;

                    if (st.lastHitLogAt <= 0L || (now - st.lastHitLogAt) >= 10L) {
                        st.lastHitLogAt = now;
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat HIT detected (villager={} blockedTick={} now={})",
                                vill.getUUID(), blockedTick, now);
                    }
                }
            } catch (Throwable ignored) {}

            st.lastHurtTimeSeen = ht;

        } catch (Throwable ignored) {}
    }

    private static boolean startBlocking(Villager vill) {
        try {
            if (vill == null) return false;
            if (vill.isUsingItem()) return true;

            ItemStack off = vill.getOffhandItem();
            if (isShieldItem(off)) {
                vill.startUsingItem(InteractionHand.OFF_HAND);
                return true;
            }

        } catch (Throwable ignored) {}

        return false;
    }

    private static CombatSettings.AiSettings getAiSettings(Villager vill) {
        try {
            CombatSettings s = CombatSettingsService.getPerVillager(vill);
            if (s == null) return new CombatSettings().ai;
            if (s.ai == null) return new CombatSettings().ai;
            return s.ai;
        } catch (Throwable ignored) {
            return new CombatSettings().ai;
        }
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static boolean shouldTryEatInCombat(Villager vill) {
        try {
            if (vill == null) return false;
            float max = vill.getMaxHealth();
            if (max <= 0.0f) return false;
            float frac = vill.getHealth() / max;
            return frac <= EAT_HP_THRESHOLD;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAnyFoodInPickupInv(Villager vill) {
        try {
            Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
            if (inv == null) return false;
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                if (st.get(DataComponents.FOOD) == null) continue;
                if (st.getUseAnimation() != UseAnim.EAT) continue;
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean startEatFromPickupInv(Villager vill, State st, long now, String kind) {
        try {
            Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
            if (inv == null) return false;

            int foodSlot = -1;
            ItemStack foodStack = ItemStack.EMPTY;
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.isEmpty()) continue;
                if (s.get(DataComponents.FOOD) == null) continue;
                if (s.getUseAnimation() != UseAnim.EAT) continue;
                foodSlot = i;
                foodStack = s;
                break;
            }
            if (foodSlot < 0 || foodStack.isEmpty()) return false;

            int useDuration = safeUseDuration(foodStack, vill);
            if (useDuration <= 0) useDuration = 32;

            // Move 1 food to mainhand (real transfer).
            ItemStack one = foodStack.copy();
            one.setCount(1);
            ItemStack remaining = foodStack.copy();
            remaining.shrink(1);
            inv.setItem(foodSlot, remaining);
            inv.setChanged();

            // Save current mainhand and equip food.
            ItemStack prevMain = vill.getMainHandItem();
            if (prevMain == null) prevMain = ItemStack.EMPTY;

            // Mark that we're intentionally mutating MAIN_HAND so your loadout service can stand down.
            // NOTE: we avoid the extra "empty hand" frame as much as possible to reduce flicker.
            allowClearHand(vill, InteractionHand.MAIN_HAND, "combat_eat_start");

            // Ensure we are not blocking when starting to eat.
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}

            // Equip food and start using (server authoritative).
            try { VillagerBrain.notifyManualHandSet(vill, EquipmentSlot.MAINHAND, one.copy(), "combat_eat_equip_food"); } catch (Throwable ignored) {}
            vill.setItemInHand(InteractionHand.MAIN_HAND, one.copy());
            vill.startUsingItem(InteractionHand.MAIN_HAND);

            st.eatPrevMain = prevMain.copy();
            st.eatFoodUsed = one.copy();
            st.eatFinishAt = now + Math.max(1, useDuration);
            st.eatNextFxAt = now + 5L;

            // eat use stability tracking
            st.eatStartAt = now;
            st.eatUseDuration = Math.max(1, useDuration);
            st.eatLastUseLogAt = 0L;
            st.eatLastUseRepairAt = 0L;

            try {
                // Used by VillagerBrain.tickRenderDecisions to set FLAG_EATING_POSE (client can use this as an animation hint).
                vill.getPersistentData().putLong("ezvr_eat_pose_until", st.eatFinishAt);
                vill.getPersistentData().putLong(PD_EAT_SLOW_UNTIL, st.eatFinishAt);
                vill.getPersistentData().putLong("ezvr_loadout_skip_main_until", st.eatFinishAt + 2L);
            } catch (Throwable ignored) {}

            // Immediately repair server-side timer to a known value (expected remaining = useDuration).
            // This helps if another system briefly interrupted use on the same tick.
            repairEatUseStateIfNeeded(vill, st, now, "start");

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] villager={} action=start kind={} item={} durationTicks={} hpBefore={}",
                    vill.getUUID(), safe(kind), safeItemId(one), useDuration, trim1(vill.getHealth()));
            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] start failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void finishEat(Villager vill, State st) {
        try {
            if (vill == null || st == null) return;

            // Try to spawn eat particles (vanilla client handler typically listens for entity event 9).
            // Fallback "final bite" burst in case periodic FX were suppressed.
            try { vill.level().broadcastEntityEvent(vill, (byte) 9); } catch (Throwable ignored) {}

            ItemStack hand = vill.getMainHandItem();
            if (hand == null) hand = ItemStack.EMPTY;

            ItemStack remainder = tryFinishUsingItem(hand, vill);
            if (remainder == null) remainder = ItemStack.EMPTY;

            float heal = computeFoodHealFromStack(st.eatFoodUsed);
            if (heal > 0.0f) {
                float before = vill.getHealth();
                try { vill.heal(heal); } catch (Throwable ignored) {}
                float after = vill.getHealth();
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] villager={} action=heal food={} heal={} hp {}->{}",
                        vill.getUUID(), safeItemId(st.eatFoodUsed), trim1(heal), trim1(before), trim1(after));
                try { org.z2six.villageroverhaul.server.VillagerHistoryService.addFoodEaten(vill, 1, heal); } catch (Throwable ignored) {}
            }

            // Restore previous mainhand.
            allowClearHand(vill, InteractionHand.MAIN_HAND, "combat_eat_finish");
            try { VillagerBrain.notifyManualHandSet(vill, EquipmentSlot.MAINHAND, ItemStack.EMPTY, "combat_eat_finish_clear"); } catch (Throwable ignored) {}
            vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (st.eatPrevMain != null && !st.eatPrevMain.isEmpty()) {
                try { VillagerBrain.notifyManualHandSet(vill, EquipmentSlot.MAINHAND, st.eatPrevMain.copy(), "combat_eat_finish_restore"); } catch (Throwable ignored) {}
                vill.setItemInHand(InteractionHand.MAIN_HAND, st.eatPrevMain.copy());
            }
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}

            // Store remainder to pickup inventory (if any) so we don't dup/drop.
            if (!remainder.isEmpty()) {
                Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
                if (inv != null) storeOrDropToPickup(vill, inv, remainder.copy());
            }

            try {
                CompoundTag pd = vill.getPersistentData();
                pd.remove("ezvr_eat_pose_until");
                pd.remove(PD_EAT_SLOW_UNTIL);
                pd.remove("ezvr_loadout_skip_main_until");
            } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] villager={} action=finish item={} hpAfter={}",
                    vill.getUUID(), safeItemId(st.eatFoodUsed), trim1(vill.getHealth()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] finish failed (soft): {}", t.toString());
        }
    }

    private static void cancelEatInProgressOnly(Villager vill, State st, String why) {
        try {
            if (vill == null || st == null) return;

            if (st.eatFinishAt > 0L) {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}

                // Refund the currently-held food back into pickup inventory if we cancel before finishing.
                try {
                    ItemStack hand = vill.getMainHandItem();
                    if (hand != null && !hand.isEmpty() && st.eatPrevMain != null && !ItemStack.isSameItemSameComponents(hand, st.eatPrevMain)) {
                        Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
                        if (inv != null) storeOrDropToPickup(vill, inv, hand.copy());
                    }
                } catch (Throwable ignored) {}

                allowClearHand(vill, InteractionHand.MAIN_HAND, "combat_eat_cancel");
                try { VillagerBrain.notifyManualHandSet(vill, EquipmentSlot.MAINHAND, ItemStack.EMPTY, "combat_eat_cancel_clear"); } catch (Throwable ignored) {}
                vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                if (st.eatPrevMain != null && !st.eatPrevMain.isEmpty()) {
                    try { VillagerBrain.notifyManualHandSet(vill, EquipmentSlot.MAINHAND, st.eatPrevMain.copy(), "combat_eat_cancel_restore"); } catch (Throwable ignored) {}
                    vill.setItemInHand(InteractionHand.MAIN_HAND, st.eatPrevMain.copy());
                }

                try {
                    CompoundTag pd = vill.getPersistentData();
                    pd.remove("ezvr_eat_pose_until");
                    pd.remove(PD_EAT_SLOW_UNTIL);
                    pd.remove("ezvr_loadout_skip_main_until");
                    pd.remove(PD_LOCK_YAW_UNTIL);
                    pd.remove(PD_LOCK_YAW);
                    pd.remove(PD_BACKPEDAL_UNTIL);
                    pd.remove(PD_BACKPEDAL_SPEED);
                    pd.remove(PD_CIRCLE_UNTIL);
                    pd.remove(PD_CIRCLE_SPEED);
                    pd.remove(PD_CIRCLE_DIR);
                    pd.remove(PD_CIRCLE_ZZA);
                } catch (Throwable ignored) {}

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] villager={} action=cancel why={}",
                        vill.getUUID(), safe(why));
            }

            st.eatFinishAt = -1L;
            st.eatNextFxAt = 0L;
            st.eatPrevMain = ItemStack.EMPTY;
            st.eatFoodUsed = ItemStack.EMPTY;

            // eat use stability tracking
            st.eatStartAt = -1L;
            st.eatUseDuration = 0;
            st.eatLastUseLogAt = 0L;
            st.eatLastUseRepairAt = 0L;

        } catch (Throwable ignored) {}
    }

    /**
     * Combat can interrupt item use (block/swing/hurt side effects). If we are in an active eat window,
     * keep reasserting "using item" so the client gets vanilla use animation + bite particles.
     *
     * Key change: keep the *timer* stable. If we keep restarting use, the countdown resets and animations can stall. :contentReference[oaicite:2]{index=2}
     */
    private static void ensureStillEating(Villager vill, State st, long now) {
        try {
            if (vill == null || st == null) return;
            if (st.eatFinishAt <= 0L) return;
            if (now >= st.eatFinishAt) return;

            ItemStack main = vill.getMainHandItem();
            if (main == null || main.isEmpty()) return;
            if (main.getUseAnimation() != UseAnim.EAT) return;

            boolean using = false;
            try { using = vill.isUsingItem(); } catch (Throwable ignored) { using = false; }

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            try {
                InteractionHand h = vill.getUsedItemHand();
                if (h != null) usedHand = h;
            } catch (Throwable ignored) {}

            // If something flipped us to OFF_HAND using, hard-correct back to MAIN_HAND for eating.
            if (using && usedHand != InteractionHand.MAIN_HAND) {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                using = false;
            }

            // If we're not using, start using (but immediately repair remaining ticks to expected).
            if (!using) {
                try { vill.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
            }

            // Repair server-side useItem/useItemRemaining to the expected value based on eatFinishAt.
            repairEatUseStateIfNeeded(vill, st, now, "ensure");

            // Emit bite particles periodically (vanilla uses entity event 9). This is server authoritative.
            if (st.eatNextFxAt <= 0L || now >= st.eatNextFxAt) {
                try { vill.level().broadcastEntityEvent(vill, (byte) 9); } catch (Throwable ignored) {}
                st.eatNextFxAt = now + 5L;
            }

            // Extend skip window slightly so loadout enforcement never fights the food item mid-bite.
            try {
                vill.getPersistentData().putLong("ezvr_loadout_skip_main_until", st.eatFinishAt + 2L);
                vill.getPersistentData().putLong("ezvr_eat_pose_until", st.eatFinishAt);
                vill.getPersistentData().putLong(PD_EAT_SLOW_UNTIL, st.eatFinishAt);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    private static void repairEatUseStateIfNeeded(Villager vill, State st, long now, String why) {
        try {
            if (vill == null || st == null) return;
            if (st.eatFinishAt <= 0L) return;
            if (now >= st.eatFinishAt) return;

            // Throttle repairs to avoid hammering reflection every tick.
            if (st.eatLastUseRepairAt > 0L && (now - st.eatLastUseRepairAt) < EAT_USE_REPAIR_MIN_INTERVAL_TICKS) {
                if (DEBUG_EAT_USE_STATE) maybeLogEatUseState(vill, st, now, why, false, "throttled");
                return;
            }

            ItemStack main = vill.getMainHandItem();
            if (main == null || main.isEmpty()) return;
            if (main.getUseAnimation() != UseAnim.EAT) return;

            int expected = (int) (st.eatFinishAt - now);
            if (expected < 1) expected = 1;

            int max = st.eatUseDuration > 0 ? st.eatUseDuration : safeUseDuration(main, vill);
            if (max <= 0) max = 32;
            if (expected > max) expected = max;

            boolean using = false;
            try { using = vill.isUsingItem(); } catch (Throwable ignored) { using = false; }

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            try {
                InteractionHand h = vill.getUsedItemHand();
                if (h != null) usedHand = h;
            } catch (Throwable ignored) {}

            int curRem = 0;
            try { curRem = vill.getUseItemRemainingTicks(); } catch (Throwable ignored) { curRem = 0; }

            ItemStack active = ItemStack.EMPTY;
            try { active = vill.getUseItem(); } catch (Throwable ignored) { active = ItemStack.EMPTY; }
            if (active == null) active = ItemStack.EMPTY;

            boolean activeOk = !active.isEmpty() && active.getUseAnimation() == UseAnim.EAT;
            boolean remOk = Math.abs(curRem - expected) <= 1;
            boolean handOk = !using || usedHand == InteractionHand.MAIN_HAND;

            boolean needPatch = !activeOk || !remOk || !handOk;

            // If not using, start using (but do not allow timer reset to full duration to persist).
            if (!using) {
                try { vill.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
            } else if (usedHand != InteractionHand.MAIN_HAND) {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                try { vill.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
                needPatch = true;
            }

            if (needPatch) {
                warmupUseFields();

                boolean patched = false;
                try {
                    if (FIELD_USE_ITEM != null) {
                        FIELD_USE_ITEM.set(vill, main.copy());
                        patched = true;
                    }
                    if (FIELD_USE_ITEM_REMAINING != null) {
                        FIELD_USE_ITEM_REMAINING.setInt(vill, expected);
                        patched = true;
                    }
                } catch (Throwable ignored) {}

                st.eatLastUseRepairAt = now;

                if (DEBUG_EAT_USE_STATE) {
                    maybeLogEatUseState(vill, st, now, why, patched, "patched");
                }
            } else {
                if (DEBUG_EAT_USE_STATE) maybeLogEatUseState(vill, st, now, why, false, "ok");
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] repairEatUseStateIfNeeded failed (soft): {}", t.toString());
        }
    }

    private static void maybeLogEatUseState(Villager vill, State st, long now, String why, boolean didPatch, String note) {
        try {
            if (!DEBUG_EAT_USE_STATE) return;
            if (vill == null || st == null) return;

            if (st.eatLastUseLogAt > 0L && (now - st.eatLastUseLogAt) < DEBUG_EAT_USE_LOG_INTERVAL_TICKS) return;
            st.eatLastUseLogAt = now;

            boolean using = false;
            try { using = vill.isUsingItem(); } catch (Throwable ignored) { using = false; }

            InteractionHand usedHand = InteractionHand.MAIN_HAND;
            try {
                InteractionHand h = vill.getUsedItemHand();
                if (h != null) usedHand = h;
            } catch (Throwable ignored) {}

            ItemStack main = vill.getMainHandItem();
            if (main == null) main = ItemStack.EMPTY;

            ItemStack active = ItemStack.EMPTY;
            try { active = vill.getUseItem(); } catch (Throwable ignored) { active = ItemStack.EMPTY; }
            if (active == null) active = ItemStack.EMPTY;

            int curRem = 0;
            try { curRem = vill.getUseItemRemainingTicks(); } catch (Throwable ignored) { curRem = 0; }

            int expected = (int) (st.eatFinishAt - now);
            if (expected < 0) expected = 0;

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] [combat_eat_use] villager={} now={} why={} using={} usedHand={} curRem={} expectedRem={} main={} active={} patched={} note={}",
                    vill.getUUID(),
                    now,
                    safe(why),
                    using,
                    usedHand.name(),
                    curRem,
                    expected,
                    safeItemId(main),
                    (active.isEmpty() ? "empty" : safeItemId(active)),
                    didPatch,
                    safe(note)
            );
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

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] [combat_eat] warmupUseFields done useItemField={} useItemRemainingField={}",
                    (FIELD_USE_ITEM != null),
                    (FIELD_USE_ITEM_REMAINING != null)
            );

        } catch (Throwable ignored) {
            FIELD_USE_ITEM = null;
            FIELD_USE_ITEM_REMAINING = null;
        }
    }

    private static void cancelEatProcess(Villager vill, State st, String why) {
        try {
            cancelEatInProgressOnly(vill, st, why);
            st.eatPhase = EatPhase.NONE;
            st.eatResetCount = 0;
            st.eatHitSeenAt = st.lastHitAt;
            st.eatBackpedalHitCount = 0;
            st.eatBlockedSeenAt = -1L;

            // eat use stability tracking
            st.eatStartAt = -1L;
            st.eatUseDuration = 0;
            st.eatLastUseLogAt = 0L;
            st.eatLastUseRepairAt = 0L;

        } catch (Throwable ignored) {}
    }

    private static void lockYaw(Villager vill, long now, float yaw) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            // Keep this refreshed every combat tick so vanilla movement/hurt logic can't rotate the villager away.
            pd.putLong(PD_LOCK_YAW_UNTIL, now + 5L);
            pd.putFloat(PD_LOCK_YAW, yaw);
        } catch (Throwable ignored) {}
    }

    private static void applyBackpedalInput(Villager vill, long now, float speed) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            pd.putLong(PD_BACKPEDAL_UNTIL, now + 2L);
            pd.putFloat(PD_BACKPEDAL_SPEED, speed);
        } catch (Throwable ignored) {}
    }

    private static void applyCircleInput(Villager vill, long now, float speed, float dir, float zza) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            pd.putLong(PD_CIRCLE_UNTIL, now + 2L);
            pd.putFloat(PD_CIRCLE_SPEED, speed);
            pd.putFloat(PD_CIRCLE_DIR, dir);
            pd.putFloat(PD_CIRCLE_ZZA, zza);
        } catch (Throwable ignored) {}
    }

    private static float pickCircleDir(Villager vill, State st, long now) {
        try {
            if (st == null) return 1.0f;
            if (st.circleDir == 0.0f) {
                st.circleDir = (vill != null && vill.getRandom().nextBoolean()) ? 1.0f : -1.0f;
                st.nextCircleSwitchAt = now + 30L + (vill == null ? 0 : vill.getRandom().nextInt(60));
            }

            if (now >= st.nextCircleSwitchAt) {
                if (vill != null && vill.getRandom().nextBoolean()) st.circleDir = -st.circleDir;
                st.nextCircleSwitchAt = now + 30L + (vill == null ? 0 : vill.getRandom().nextInt(60));
            }

            return st.circleDir == 0.0f ? 1.0f : st.circleDir;
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static boolean canBackpedalBehind(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return true;
            Level level = vill.level();
            if (level == null) return true;

            Vec3 vp = vill.position();
            Vec3 tp = target.position();

            Vec3 away = vp.subtract(tp);
            Vec3 away2d = new Vec3(away.x, 0.0, away.z);
            if (away2d.lengthSqr() < 1.0e-6) return true;

            Vec3 dir = away2d.normalize();

            // Sample a couple of points behind us.
            if (!isStepSafe(vill, dir.scale(0.75))) return false;
            if (!isStepSafe(vill, dir.scale(1.40))) return false;
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isStepSafe(Villager vill, Vec3 delta) {
        try {
            if (vill == null || delta == null) return true;
            Level level = vill.level();
            if (level == null) return true;

            Vec3 dest = vill.position().add(delta);

            // Collision check for the moved bounding box.
            AABB moved = vill.getBoundingBox().move(delta);
            if (!level.noCollision(vill, moved)) return false;

            // Ground check to avoid backing into ravines/holes.
            BlockPos below = BlockPos.containing(dest.x, dest.y - 0.05, dest.z).below();
            BlockState bs = level.getBlockState(below);
            if (bs == null) return false;
            if (bs.getCollisionShape(level, below).isEmpty()) return false;

            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static double computeMaintainDistance(double reach) {
        double desired = 2.0;
        if (reach > 0.0) desired = Math.max(2.0, reach - 1.0);
        return desired;
    }

    private static void allowClearHand(Villager vill, InteractionHand hand, String reason) {
        try {
            if (vill == null || hand == null) return;
            EquipmentSlot slot = (hand == InteractionHand.MAIN_HAND) ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            VillagerBrain.notifyManualHandSet(vill, slot, ItemStack.EMPTY, reason);
        } catch (Throwable ignored) {}
    }

    private static ItemStack tryFinishUsingItem(ItemStack stack, LivingEntity user) {
        try {
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
            if (user == null || user.level() == null) return ItemStack.EMPTY;

            if (!FINISH_SCANNED) {
                FINISH_SCANNED = true;
                try {
                    for (Method m : ItemStack.class.getMethods()) {
                        if (!"finishUsingItem".equals(m.getName())) continue;
                        if (m.getParameterCount() != 2) continue;
                        m.setAccessible(true);
                        FINISH_USING_ITEM = m;
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            if (FINISH_USING_ITEM == null) return ItemStack.EMPTY;
            Object out = FINISH_USING_ITEM.invoke(stack, user.level(), user);
            if (out instanceof ItemStack st) return st;
            return ItemStack.EMPTY;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [combat_eat] finishUsingItem reflection failed (soft): {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    private static int safeUseDuration(ItemStack stack, LivingEntity user) {
        try {
            if (stack == null || stack.isEmpty()) return 0;
            if (!USE_DUR_SCANNED) {
                USE_DUR_SCANNED = true;
                try {
                    for (Method m : ItemStack.class.getMethods()) {
                        if (!"getUseDuration".equals(m.getName())) continue;
                        if (m.getReturnType() != int.class) continue;
                        if (m.getParameterCount() == 0) {
                            m.setAccessible(true);
                            GET_USE_DURATION_0 = m;
                        } else if (m.getParameterCount() == 1) {
                            m.setAccessible(true);
                            GET_USE_DURATION_1 = m;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (GET_USE_DURATION_1 != null) {
                Object out = GET_USE_DURATION_1.invoke(stack, user);
                if (out instanceof Integer i) return i;
            }
            if (GET_USE_DURATION_0 != null) {
                Object out = GET_USE_DURATION_0.invoke(stack);
                if (out instanceof Integer i) return i;
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float computeFoodHealFromStack(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return 0.0f;
            Object food = stack.get(DataComponents.FOOD);
            if (food == null) return 0.0f;

            int nutrition = 0;
            float satMod = 0.0f;

            try {
                Method m = food.getClass().getMethod("nutrition");
                Object out = m.invoke(food);
                if (out instanceof Integer i) nutrition = i;
            } catch (Throwable ignored) {}

            try {
                Method m = food.getClass().getMethod("saturationModifier");
                Object out = m.invoke(food);
                if (out instanceof Float f) satMod = f;
                else if (out instanceof Double d) satMod = d.floatValue();
            } catch (Throwable ignored) {}

            if (nutrition <= 0) return 0.0f;

            float heal = (float) nutrition;
            if (satMod > 0.0f) {
                heal += Math.min(4.0f, satMod * 2.0f);
            }

            return Math.max(0.0f, heal);
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private static void storeOrDropToPickup(Villager vill, Container inv, ItemStack stack) {
        try {
            if (vill == null || inv == null) return;
            if (stack == null || stack.isEmpty()) return;

            ItemStack remaining = stack.copy();
            int size = inv.getContainerSize();
            for (int i = 0; i < size && !remaining.isEmpty(); i++) {
                ItemStack slot = inv.getItem(i);
                if (slot == null || slot.isEmpty()) {
                    inv.setItem(i, remaining.copy());
                    remaining = ItemStack.EMPTY;
                    break;
                }
                if (ItemStack.isSameItemSameComponents(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                    int can = Math.min(remaining.getCount(), slot.getMaxStackSize() - slot.getCount());
                    if (can > 0) {
                        slot.grow(can);
                        remaining.shrink(can);
                        inv.setItem(i, slot);
                    }
                }
            }
            inv.setChanged();

            if (!remaining.isEmpty()) {
                vill.spawnAtLocation(remaining.copy(), 0.1f);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean shouldBackpedalInCombat(Villager vill, LivingEntity target, double dist) {
        try {
            if (vill == null || target == null) return false;

            double tooClose = computeTooCloseDist(vill, target);
            if (stoppedOrNearlyStopped(vill)) {
                return dist <= tooClose;
            }

            return dist <= (tooClose + TOO_CLOSE_HYSTERESIS);
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean stoppedOrNearlyStopped(Villager vill) {
        try {
            Vec3 d = vill.getDeltaMovement();
            return d.lengthSqr() < 1.0e-4;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static double computeTooCloseDist(Villager vill, LivingEntity target) {
        try {
            double v = vill == null ? 0.6 : vill.getBbWidth();
            double t = target == null ? 0.6 : target.getBbWidth();
            return Math.max(1.0, (v * 0.5) + (t * 0.5) + TOO_CLOSE_PAD);
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    private static void backpedalAway(Villager vill, LivingEntity target, double speed, double step) {
        try {
            if (vill == null || target == null) return;
            Vec3 vp = vill.position();
            Vec3 tp = target.position();

            Vec3 away = vp.subtract(tp);
            Vec3 away2d = new Vec3(away.x, 0.0, away.z);
            if (away2d.lengthSqr() < 1.0e-6) away2d = new Vec3(1.0, 0.0, 0.0);

            Vec3 dir = away2d.normalize();
            Vec3 dest = vp.add(dir.scale(step));
            vill.getNavigation().moveTo(dest.x, dest.y, dest.z, speed);
        } catch (Throwable ignored) {}
    }

    private static void runAwayFrom(Villager vill, LivingEntity target, double speed, double step) {
        try {
            if (vill == null || target == null) return;
            Vec3 vp = vill.position();
            Vec3 tp = target.position();

            Vec3 away = vp.subtract(tp);
            Vec3 away2d = new Vec3(away.x, 0.0, away.z);
            if (away2d.lengthSqr() < 1.0e-6) away2d = new Vec3(1.0, 0.0, 0.0);

            Vec3 dir = away2d.normalize();
            Vec3 dest = vp.add(dir.scale(step));
            vill.getNavigation().moveTo(dest.x, dest.y, dest.z, speed);
        } catch (Throwable ignored) {}
    }

    private static void faceAwayFromTargetHard(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return;

            Vec3 vp = vill.position();
            Vec3 tp = target.position();

            double dx = tp.x - vp.x;
            double dz = tp.z - vp.z;

            if ((dx * dx + dz * dz) < 1.0e-6) return;

            float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            desiredYaw = Mth.wrapDegrees(desiredYaw + 180.0f);

            try { vill.setYRot(desiredYaw); } catch (Throwable ignored) {}
            try { vill.yRotO = desiredYaw; } catch (Throwable ignored) {}

            if (!ROT_REFLECT_SCANNED) warmupRotReflection();
            tryInvokeYawSetter(SET_Y_HEAD_ROT, vill, desiredYaw);
            tryInvokeYawSetter(SET_Y_BODY_ROT, vill, desiredYaw);

        } catch (Throwable ignored) {}
    }

    private static double computeReach(Villager vill, LivingEntity target) {
        double r = tryGetReachAttribute(vill);
        if (r > 0.0) return r;
        double v = (vill == null) ? 0.6 : vill.getBbWidth();
        double t = (target == null) ? 0.6 : target.getBbWidth();
        return BASE_REACH + (v * 0.5) + (t * 0.5);
    }

    private static volatile boolean REACH_ATTR_SCANNED = false;
    private static volatile Holder<Attribute> REACH_ATTR = null;

    private static void enterCombatIfNeeded(Villager vill, State st) {
        try {
            if (vill == null || st == null) return;
            if (!VillagerBrain.isCombatEngaged(vill)) {
                st.combatStartPos = vill.position();
                resetUnreachableState(st);
            }
            VillagerBrain.setCombatEngaged(vill, true);
        } catch (Throwable ignored) {}
    }

    private static boolean shouldAbortUnreachableCombat(Villager vill, LivingEntity target, State st, long now, RangedLoadout ranged, CombatSettings.AiSettings ai) {
        try {
            if (vill == null || target == null || st == null) return false;
            int timeoutSeconds = ai == null ? new CombatSettings().ai.targetTimeoutSeconds : ai.targetTimeoutSeconds;
            long timeoutTicks = Math.max(0L, (long) timeoutSeconds * 20L);
            if (timeoutTicks <= 0L) {
                resetUnreachableState(st);
                return false;
            }

            boolean inEngagementWindow;
            boolean hasLos = hasLineOfSightSafe(vill, target);
            double dist = vill.distanceTo(target);

            if (ranged != null && ranged.hasAmmo) {
                double preferredRange = computePreferredRangedDistance(ranged);
                double maxRange = Math.max(Math.max(RANGED_MIN_RANGE_FLOOR, preferredRange - RANGED_BACKPEDAL_RANGE_PAD) + 2.0,
                        ranged.weaponItem.getDefaultProjectileRange() + RANGED_MAX_RANGE_PAD);
                inEngagementWindow = hasLos && dist <= maxRange;
            } else {
                double swingRange = computeReach(vill, target) + SAFETY_MARGIN;
                inEngagementWindow = hasLos && dist <= swingRange;
            }

            if (inEngagementWindow) {
                resetUnreachableState(st);
                return false;
            }

            if (st.lastHitAt >= 0L && (now - st.lastHitAt) <= timeoutTicks) {
                st.unreachableSince = now;
                st.lastProgressAt = now;
                st.bestProgressDistance = dist;
                st.lastHadLineOfSight = hasLos;
                return false;
            }

            if (st.unreachableSince < 0L) {
                st.unreachableSince = now;
                st.lastProgressAt = now;
                st.bestProgressDistance = dist;
                st.lastHadLineOfSight = hasLos;
                return false;
            }

            boolean progressed = false;
            if (dist < (st.bestProgressDistance - UNREACHABLE_PROGRESS_DISTANCE)) {
                st.bestProgressDistance = dist;
                progressed = true;
            }
            if (hasLos && !st.lastHadLineOfSight) {
                progressed = true;
            }
            st.lastHadLineOfSight = hasLos;

            if (progressed) {
                st.lastProgressAt = now;
                return false;
            }

            return (now - st.lastProgressAt) >= timeoutTicks;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void resetUnreachableState(State st) {
        try {
            if (st == null) return;
            st.unreachableSince = -1L;
            st.lastProgressAt = -1L;
            st.bestProgressDistance = Double.MAX_VALUE;
            st.lastHadLineOfSight = false;
        } catch (Throwable ignored) {}
    }

    private static void markTimedOutTarget(Villager vill, State st, LivingEntity target, long now, CombatSettings.AiSettings ai) {
        try {
            if (st == null || target == null) return;
            int timeoutSeconds = ai == null ? new CombatSettings().ai.targetTimeoutSeconds : ai.targetTimeoutSeconds;
            long timeoutTicks = Math.max(0L, (long) timeoutSeconds * 20L);
            st.lastTimedOutTargetId = target.getUUID();
            st.lastTimedOutTargetUntil = now + Math.max(UNREACHABLE_TARGET_COOLDOWN_TICKS, timeoutTicks);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat target timed out (villager={} target={} cooldownTicks={})",
                    safeUuid(vill), safeUuid(target), Math.max(UNREACHABLE_TARGET_COOLDOWN_TICKS, timeoutTicks));
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static double tryGetReachAttribute(Villager vill) {
        try {
            if (vill == null) return -1.0;
            if (!REACH_ATTR_SCANNED) {
                REACH_ATTR_SCANNED = true;
                // Best-effort scan for a NeoForge reach attribute without hard depending on it.
                try {
                    Class<?> cls = Class.forName("net.neoforged.neoforge.common.NeoForgeMod");
                    Field best = null;
                    for (Field f : cls.getFields()) {
                        String n = f.getName();
                        if (n == null) continue;
                        String ln = n.toLowerCase(java.util.Locale.ROOT);
                        if (!ln.contains("reach")) continue;
                        if (best == null) best = f;
                        if (ln.contains("entity") && ln.contains("reach")) {
                            best = f;
                            break;
                        }
                    }
                    if (best != null) {
                        Object v0 = best.get(null);
                        if (v0 instanceof Holder<?> h) REACH_ATTR = (Holder) h;
                    }
                } catch (Throwable ignored) {}
            }

            if (REACH_ATTR == null) return -1.0;
            AttributeInstance inst = vill.getAttribute((Holder) REACH_ATTR);
            if (inst == null) return -1.0;
            double val = inst.getValue();
            return val > 0.0 ? val : -1.0;
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    private static boolean isShieldItem(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return false;
            UseAnim anim = st.getUseAnimation();
            return anim == UseAnim.BLOCK;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- Facing helpers ----

    private static void faceTargetHard(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return;

            Vec3 vp = vill.position();
            Vec3 tp = target.position();

            double dx = tp.x - vp.x;
            double dz = tp.z - vp.z;

            if ((dx * dx + dz * dz) < 1.0e-6) return;

            float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            desiredYaw = Mth.wrapDegrees(desiredYaw);

            try { vill.setYRot(desiredYaw); } catch (Throwable ignored) {}
            try { vill.yRotO = desiredYaw; } catch (Throwable ignored) {}

            if (!ROT_REFLECT_SCANNED) warmupRotReflection();
            tryInvokeYawSetter(SET_Y_HEAD_ROT, vill, desiredYaw);
            tryInvokeYawSetter(SET_Y_BODY_ROT, vill, desiredYaw);

        } catch (Throwable ignored) {}
    }

    private static void warmupRotReflection() {
        ROT_REFLECT_SCANNED = true;
        try {
            for (Method m : LivingEntity.class.getMethods()) {
                if (m == null) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != float.class) continue;

                String n = m.getName();
                if (n == null) continue;

                if (SET_Y_HEAD_ROT == null && (n.equals("setYHeadRot") || n.toLowerCase().contains("yheadrot"))) {
                    SET_Y_HEAD_ROT = m;
                }
                if (SET_Y_BODY_ROT == null && (n.equals("setYBodyRot") || n.toLowerCase().contains("ybodyrot"))) {
                    SET_Y_BODY_ROT = m;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void tryInvokeYawSetter(Method m, LivingEntity e, float yaw) {
        try {
            if (m == null || e == null) return;
            m.invoke(e, yaw);
        } catch (Throwable ignored) {}
    }

    // ---- existing helpers ----

    private static String safeItemId(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "empty";
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            return id == null ? "unknown" : id.toString();
        } catch (Throwable t) {
            return "error";
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        s = s.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    private static String safeUuid(Object e) {
        try {
            if (e instanceof LivingEntity le) return String.valueOf(le.getUUID());
            if (e instanceof Villager v) return String.valueOf(v.getUUID());
        } catch (Throwable ignored) {}
        return "null";
    }

    private static double trim3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double trim1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10.0) / 10.0;
    }

    private static final class State {
        java.util.UUID targetId;
        Vec3 combatStartPos = null;
        long uiPausedAt = -1L;

        long nextSwingAt = 0L;
        long lastSwingAt = -9999L;
        boolean rangedCharging = false;
        long rangedChargeStartAt = -1L;

        long noBlockUntil = 0L;

        boolean hitSinceLastSwing = false;
        long lastHitAt = -1L;

        int lastHurtTimeSeen = 0;
        long lastBlockedTickSeen = 0L;

        long blockNoHitSince = -1L;

        long lastHitLogAt = 0L;
        long lastSwingReasonLogAt = 0L;

        EatPhase eatPhase = EatPhase.NONE;
        int eatResetCount = 0;
        long eatHitSeenAt = -1L;
        int eatBackpedalHitCount = 0;
        long eatBlockedSeenAt = -1L;

        long eatFinishAt = -1L;
        long eatNextFxAt = 0L;
        ItemStack eatPrevMain = ItemStack.EMPTY;
        ItemStack eatFoodUsed = ItemStack.EMPTY;

        // Circling behavior (combat movement)
        float circleDir = 0.0f;
        long nextCircleSwitchAt = 0L;

        // --- eat use stability tracking ---
        long eatStartAt = -1L;
        int eatUseDuration = 0;
        long eatLastUseLogAt = 0L;
        long eatLastUseRepairAt = 0L;

        long unreachableSince = -1L;
        long lastProgressAt = -1L;
        double bestProgressDistance = Double.MAX_VALUE;
        boolean lastHadLineOfSight = false;

        java.util.UUID lastTimedOutTargetId = null;
        long lastTimedOutTargetUntil = 0L;
    }

    private static final class RangedLoadout {
        final ItemStack weaponStack;
        final ItemStack ammoStack;
        final ProjectileWeaponItem weaponItem;
        final boolean crossbowLike;
        final boolean hasAmmo;

        private RangedLoadout(ItemStack weaponStack, ItemStack ammoStack, ProjectileWeaponItem weaponItem, boolean crossbowLike, boolean hasAmmo) {
            this.weaponStack = weaponStack;
            this.ammoStack = ammoStack;
            this.weaponItem = weaponItem;
            this.crossbowLike = crossbowLike;
            this.hasAmmo = hasAmmo;
        }
    }

    private enum EatPhase {
        NONE,
        BACKPEDAL,
        BACKPEDAL_EAT,
        RUN,
        EAT,
        FORCE_EAT
    }
}
