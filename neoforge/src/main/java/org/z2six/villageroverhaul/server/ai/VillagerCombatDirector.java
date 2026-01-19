// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDirector.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulSwingAccess;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class VillagerCombatDirector {

    private static final boolean ENABLE_BLOCKING = true;

    private static final double MOVE_SPEED = 0.70;
    private static final double BACKPEDAL_SPEED = MOVE_SPEED * 0.25;
    private static final double BASE_REACH = 2.0;
    private static final double SAFETY_MARGIN = 0.5;

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

    private VillagerCombatDirector() {}

    public static void tickAttack(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!target.isAlive()) return;

            State st = STATE.computeIfAbsent(vill, v -> new State());
            if (st.targetId != null && !st.targetId.equals(target.getUUID())) {
                st.hitSinceLastSwing = false;
                st.blockNoHitSince = -1L;
                st.lastHurtTimeSeen = 0;
                st.lastHitAt = -1L;
            }
            st.targetId = target.getUUID();

            if (!VillagerBrain.shouldCombatActNow(vill) || VillagerBrain.isUiPaused(vill)) {
                stop(vill);
                return;
            }

            VillagerBrain.setCombatEngaged(vill, true);

            long now = vill.level().getGameTime();

            detectHitEdge(vill, st, now);

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
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
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
    }
}
