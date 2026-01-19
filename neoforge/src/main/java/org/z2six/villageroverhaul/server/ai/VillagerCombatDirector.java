// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDirector.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;
import org.z2six.villageroverhaul.menu.VillagerInventoryMenu;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class VillagerCombatDirector {

    private static final boolean ENABLE_BLOCKING = true;

    private static final double MOVE_SPEED = 0.70;
    private static final double BACKPEDAL_SPEED = MOVE_SPEED * 0.25;
    private static final double BASE_REACH = 2.0;
    private static final double SAFETY_MARGIN = 0.5;

    private static final float EAT_HP_THRESHOLD = 0.65f;
    private static final double EAT_BACKPEDAL_SPEED = 0.50;
    private static final double EAT_RUN_SPEED = 0.65;
    private static final double EAT_DIST_RUN_START = 3.0;
    private static final double EAT_DIST_START_EATING = 5.0;
    private static final int EAT_MAX_RESETS = 1;
    private static final int EAT_BACKPEDAL_HIT_FORCE_EAT = 0;
    private static final double EAT_BACKPEDAL_FORCE_EAT_SPEED = 0.50;

    private static final String PD_BLOCKED_TICK = "ezvr_blocked_tick";
    private static final String PD_LOCK_YAW_UNTIL = "ezvr_lock_yaw_until";
    private static final String PD_LOCK_YAW = "ezvr_lock_yaw";
    private static final String PD_BACKPEDAL_UNTIL = "ezvr_backpedal_until";
    private static final String PD_BACKPEDAL_SPEED = "ezvr_backpedal_speed";

    private static final long SWING_COOLDOWN_TICKS = 30L;
    private static final long SWING_ANIM_TICKS = 6L;
    private static final long NO_HIT_SWING_DELAY_TICKS = 30L;

    private static final double TOO_CLOSE_PAD = 0.30;
    private static final double TOO_CLOSE_HYSTERESIS = 0.40;

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

    private VillagerCombatDirector() {}

    public static void tickAttack(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!target.isAlive()) {
                State st = STATE.get(vill);
                if (st != null) cancelEatProcess(vill, st, "target_dead");
                return;
            }

            State st = STATE.computeIfAbsent(vill, v -> new State());
            if (st.targetId != null && !st.targetId.equals(target.getUUID())) {
                st.hitSinceLastSwing = false;
                st.blockNoHitSince = -1L;
                st.lastHurtTimeSeen = 0;
                st.lastHitAt = -1L;
                st.eatPhase = EatPhase.NONE;
                st.eatResetCount = 0;
                st.eatHitSeenAt = -1L;
            }
            st.targetId = target.getUUID();

            if (!VillagerBrain.shouldCombatActNow(vill) || VillagerBrain.isUiPaused(vill)) {
                stop(vill);
                return;
            }

            VillagerBrain.setCombatEngaged(vill, true);

            long now = vill.level().getGameTime();

            detectHitEdge(vill, st, now);

            // Low HP: prioritize escape-to-eat loop while we have food available.
            if (shouldTryEatInCombat(vill) && hasAnyFoodInPickupInv(vill)) {
                if (tickEatEscapeProcess(vill, target, st, now)) return;
            } else {
                // If we recovered above threshold or have no food, ensure we don't keep stale state.
                if (st.eatPhase != EatPhase.NONE) cancelEatProcess(vill, st, "eat_abort_nofood_or_recovered");
            }

            double reach = computeReach(vill, target);
            double swingRange = reach + SAFETY_MARGIN;

            double dist = vill.distanceTo(target);
            boolean inSwingRange = dist <= swingRange;

            if (!inSwingRange) {
                try { vill.getNavigation().moveTo(target, MOVE_SPEED); } catch (Throwable ignored) {}
            } else {
                if (shouldBackpedalInCombat(vill, target, dist)) {
                    backpedalAway(vill, target, BACKPEDAL_SPEED, 3.5);
                } else {
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
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
                return;
            }

            if (ENABLE_BLOCKING) {
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

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatDirector.tickAttack failed (soft): {}", t.toString());
        }
    }

    public static void stop(Villager vill) {
        try {
            if (vill == null) return;
            State st = STATE.get(vill);
            if (st != null) cancelEatProcess(vill, st, "stop");
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
    }

    private static boolean tickEatEscapeProcess(Villager vill, LivingEntity target, State st, long now) {
        try {
            if (vill == null || target == null || st == null) return false;

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

                if (EAT_BACKPEDAL_HIT_FORCE_EAT <= 0) {
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
                    if (st.eatBackpedalHitCount >= EAT_BACKPEDAL_HIT_FORCE_EAT) {
                        st.eatPhase = EatPhase.BACKPEDAL_EAT;
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] villager={} action=backpedal_force_eat hits={}",
                                vill.getUUID(), st.eatBackpedalHitCount);
                    }
                } else if (st.eatPhase != EatPhase.FORCE_EAT && st.eatPhase != EatPhase.BACKPEDAL_EAT) {
                    st.eatResetCount++;
                    cancelEatInProgressOnly(vill, st, "eat_reset_hit");
                    if (st.eatResetCount >= EAT_MAX_RESETS) {
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
                        if (st.eatBackpedalHitCount >= EAT_BACKPEDAL_HIT_FORCE_EAT) {
                            st.eatPhase = EatPhase.BACKPEDAL_EAT;
                            VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] villager={} action=backpedal_force_eat why=blocked hits={}",
                                    vill.getUUID(), st.eatBackpedalHitCount);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            double dist = vill.distanceTo(target);

            switch (st.eatPhase) {
                case BACKPEDAL -> {
                    // Backpedal slowly while shielding and facing target.
                    startBlocking(vill);
                    faceTargetHard(vill, target);
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    applyBackpedalInput(vill, now, (float) EAT_BACKPEDAL_SPEED);

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

                    if (ENABLE_BLOCKING) {
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
                    applyBackpedalInput(vill, now, (float) EAT_BACKPEDAL_FORCE_EAT_SPEED);
                    try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}
                    lockYaw(vill, now, vill.getYRot());

                    if (st.eatFinishAt <= 0L) {
                        // Ensure we are not blocking when starting to eat.
                        try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                        if (!startEatFromPickupInv(vill, st, now, "backpedal_force")) {
                            cancelEatProcess(vill, st, "eat_start_failed");
                            return false;
                        }
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
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    faceTargetHard(vill, target);
                    try { vill.getLookControl().setLookAt(target, 30.0f, 30.0f); } catch (Throwable ignored) {}

                    if (st.eatFinishAt <= 0L) {
                        if (!startEatFromPickupInv(vill, st, now, st.eatPhase == EatPhase.FORCE_EAT ? "force" : "normal")) {
                            // No food after all; fall back to combat.
                            cancelEatProcess(vill, st, "eat_start_failed");
                            return false;
                        }
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

                    }
                }
                default -> {}
            }

            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatDirector.tickEatEscapeProcess failed (soft): {}", t.toString());
            return false;
        }
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
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat SWING reason={} villager={} target={}",
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
                VillagerOverhaul.LOG().info("[VillagerOverhaul] doHurtTarget threw (villager={} target={} err={})",
                        safeUuid(vill), safeUuid(target), t.toString());
                hit = false;
            }

            float afterHp = -1.0f;
            try { afterHp = target.getHealth(); } catch (Throwable ignored) {}

            st.nextSwingAt = now + SWING_COOLDOWN_TICKS;
            st.lastSwingAt = now;

            VillagerOverhaul.LOG().info(
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
            VillagerOverhaul.LOG().info("[VillagerOverhaul] doSwingAndHit failed (soft): {}", t.toString());
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
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat HIT detected (villager={} hurtTime={} now={})",
                            vill.getUUID(), ht, now);
                }
            }

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

            ItemStack main = vill.getMainHandItem();
            if (isShieldItem(main)) {
                vill.startUsingItem(InteractionHand.MAIN_HAND);
                return true;
            }

        } catch (Throwable ignored) {}

        return false;
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

            allowClearHand(vill, InteractionHand.MAIN_HAND, "combat_eat_start");
            vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            vill.setItemInHand(InteractionHand.MAIN_HAND, one.copy());
            vill.startUsingItem(InteractionHand.MAIN_HAND);

            st.eatPrevMain = prevMain.copy();
            st.eatFoodUsed = one.copy();
            st.eatFinishAt = now + Math.max(1, useDuration);

            try {
                vill.getPersistentData().putLong("ezvr_loadout_skip_main_until", st.eatFinishAt + 2L);
            } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] villager={} action=start kind={} item={} durationTicks={} hpBefore={}",
                    vill.getUUID(), safe(kind), safeItemId(one), useDuration, trim1(vill.getHealth()));
            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] start failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void finishEat(Villager vill, State st) {
        try {
            if (vill == null || st == null) return;

            // Try to spawn eat particles (vanilla client handler typically listens for entity event 9).
            try {
                vill.level().broadcastEntityEvent(vill, (byte) 9);
            } catch (Throwable ignored) {}

            ItemStack hand = vill.getMainHandItem();
            if (hand == null) hand = ItemStack.EMPTY;

            ItemStack remainder = tryFinishUsingItem(hand, vill);
            if (remainder == null) remainder = ItemStack.EMPTY;

            float heal = computeFoodHealFromStack(st.eatFoodUsed);
            if (heal > 0.0f) {
                float before = vill.getHealth();
                try { vill.heal(heal); } catch (Throwable ignored) {}
                float after = vill.getHealth();
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] villager={} action=heal food={} heal={} hp {}->{}",
                        vill.getUUID(), safeItemId(st.eatFoodUsed), trim1(heal), trim1(before), trim1(after));
            }

            // Restore previous mainhand.
            allowClearHand(vill, InteractionHand.MAIN_HAND, "combat_eat_finish");
            vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (st.eatPrevMain != null && !st.eatPrevMain.isEmpty()) {
                vill.setItemInHand(InteractionHand.MAIN_HAND, st.eatPrevMain.copy());
            }

            // Store remainder to pickup inventory (if any) so we don't dup/drop.
            if (!remainder.isEmpty()) {
                Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
                if (inv != null) storeOrDropToPickup(vill, inv, remainder.copy());
            }

            try {
                CompoundTag pd = vill.getPersistentData();
                pd.remove("ezvr_loadout_skip_main_until");
            } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] villager={} action=finish item={} hpAfter={}",
                    vill.getUUID(), safeItemId(st.eatFoodUsed), trim1(vill.getHealth()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] finish failed (soft): {}", t.toString());
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
                vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                if (st.eatPrevMain != null && !st.eatPrevMain.isEmpty()) {
                    vill.setItemInHand(InteractionHand.MAIN_HAND, st.eatPrevMain.copy());
                }

                try {
                    CompoundTag pd = vill.getPersistentData();
                    pd.remove("ezvr_loadout_skip_main_until");
                    pd.remove(PD_LOCK_YAW_UNTIL);
                    pd.remove(PD_LOCK_YAW);
                    pd.remove(PD_BACKPEDAL_UNTIL);
                    pd.remove(PD_BACKPEDAL_SPEED);
                } catch (Throwable ignored) {}

                VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] villager={} action=cancel why={}",
                        vill.getUUID(), safe(why));
            }

            st.eatFinishAt = -1L;
            st.eatPrevMain = ItemStack.EMPTY;
            st.eatFoodUsed = ItemStack.EMPTY;

        } catch (Throwable ignored) {}
    }

    private static void cancelEatProcess(Villager vill, State st, String why) {
        try {
            cancelEatInProgressOnly(vill, st, why);
            st.eatPhase = EatPhase.NONE;
            st.eatResetCount = 0;
            st.eatHitSeenAt = st.lastHitAt;
            st.eatBackpedalHitCount = 0;
            st.eatBlockedSeenAt = -1L;
        } catch (Throwable ignored) {}
    }

    private static void lockYaw(Villager vill, long now, float yaw) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            pd.putLong(PD_LOCK_YAW_UNTIL, now + 2L);
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
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [combat_eat] finishUsingItem reflection failed (soft): {}", t.toString());
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
        double v = (vill == null) ? 0.6 : vill.getBbWidth();
        double t = (target == null) ? 0.6 : target.getBbWidth();
        return BASE_REACH + (v * 0.5) + (t * 0.5);
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

        long nextSwingAt = 0L;
        long lastSwingAt = -9999L;

        long noBlockUntil = 0L;

        boolean hitSinceLastSwing = false;
        long lastHitAt = -1L;

        int lastHurtTimeSeen = 0;

        long blockNoHitSince = -1L;

        long lastHitLogAt = 0L;
        long lastSwingReasonLogAt = 0L;

        EatPhase eatPhase = EatPhase.NONE;
        int eatResetCount = 0;
        long eatHitSeenAt = -1L;
        int eatBackpedalHitCount = 0;
        long eatBlockedSeenAt = -1L;

        long eatFinishAt = -1L;
        ItemStack eatPrevMain = ItemStack.EMPTY;
        ItemStack eatFoodUsed = ItemStack.EMPTY;
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
