// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDirector.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class VillagerCombatDirector {

    /**
     * Re-enabled.
     * The policy is now:
     * - default state is BLOCKING
     * - SWING is an interrupt (triggered by: got hit OR timeout-without-hit)
     */
    private static final boolean ENABLE_BLOCKING = true;

    private static final double MOVE_SPEED = 0.70;
    private static final double BASE_REACH = 2.0;
    private static final double SAFETY_MARGIN = 0.5;

    /**
     * Server combat cadence (damage cadence).
     * This is independent from the visual swing duration on the client.
     */
    private static final long SWING_COOLDOWN_TICKS = 15L;

    /**
     * Match your client visual swing window (currently 6).
     * We do NOT block during this window so the arms don't snap into block pose.
     */
    private static final long SWING_ANIM_TICKS = 6L;

    /**
     * Rule #4: if not hit while blocking for ~1.5 seconds, swing.
     */
    private static final long NO_HIT_SWING_DELAY_TICKS = 30L; // 1.5s

    private static final double EAT_RETREAT_DIST = 6.0;
    private static final double EAT_MIN_DIST_BUFFER = 1.5;

    private static final Map<Villager, State> STATE = new WeakHashMap<>();

    private VillagerCombatDirector() {}

    public static void tickAttack(Villager vill, LivingEntity target) {
        try {
            if (vill == null || target == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!target.isAlive()) return;

            State st = STATE.computeIfAbsent(vill, v -> new State());
            st.targetId = target.getUUID();

            if (!VillagerBrain.shouldCombatActNow(vill) || VillagerBrain.isUiPaused(vill)) {
                stop(vill);
                return;
            }

            VillagerBrain.setCombatEngaged(vill, true);

            long now = vill.level().getGameTime();

            // Detect “got hit” (even if damage was 0) using hurtTime rising edge.
            detectHitEdge(vill, st, now);

            // Eating logic (unchanged)
            if (st.eating) {
                if (!vill.isUsingItem()) {
                    finishEating(vill, st);
                }
            }

            if (!st.eating && shouldEat(vill)) {
                if (tryStartEating(vill, st)) {
                    // While eating we keep distance and do not block/swing.
                    return;
                }
            }

            double reach = computeReach(vill, target);
            double swingRange = reach + SAFETY_MARGIN;

            if (st.eating) {
                keepDistanceWhileEating(vill, target, swingRange + EAT_MIN_DIST_BUFFER);
                return;
            }

            double dist = vill.distanceTo(target);
            boolean inSwingRange = dist <= swingRange;

            // Movement policy:
            // - If out of range: move toward target (while blocking)
            // - If in range: stop navigation (block stance + swing interrupts)
            if (!inSwingRange) {
                try { vill.getNavigation().moveTo(target, MOVE_SPEED); } catch (Throwable ignored) {}
            } else {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            }

            // If we are within the post-swing “no block” window, ensure we are not using item.
            // This makes blocking resume only after the swing animation is “complete”.
            if (now < st.noBlockUntil) {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            }

            // Decide if we should swing now (Rule #3 and #4).
            // - Must be in range to swing.
            // - Must not be in the no-block window (i.e., previous swing anim still playing).
            // - Must respect cooldown.
            boolean canSwingNow = inSwingRange && now >= st.noBlockUntil && now >= st.nextSwingAt;

            boolean wantSwing = false;
            String swingReason = null;

            // Rule #3: if villager has been hit once -> swing
            if (st.hitSinceLastSwing) {
                wantSwing = true;
                swingReason = "hit";
            } else {
                // Rule #4: if not hit while blocking for ~1.5s -> swing
                // Only count time while we're in "combat loop" and effectively blocking.
                // (We reset this timer when we swing or when we get hit.)
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
                // Swing is an interrupt: stop blocking immediately, swing, then re-block after SWING_ANIM_TICKS.
                performSwing(vill, target, st, now, swingReason);

                // After we swing, we intentionally do not block until noBlockUntil.
                // Blocking will resume automatically below on subsequent ticks.
                return;
            }

            // Default state: BLOCKING (Rule #1 and #2).
            // - Out of range => keep blocking while pathing
            // - In range => keep blocking unless we are currently in a swing “no-block” window
            if (ENABLE_BLOCKING) {
                if (now >= st.noBlockUntil) {
                    // Start/maintain blocking.
                    boolean started = startBlocking(vill);
                    if (started) {
                        // When we (re-)enter blocking state, start/reset the no-hit timer.
                        if (st.blockNoHitSince < 0L) st.blockNoHitSince = now;
                    }
                } else {
                    // Still in swing animation window: do not block.
                    try { vill.stopUsingItem(); } catch (Throwable ignored) {}
                }
            } else {
                try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            }

            // Keep looking at target (helps block-angle later).
            try {
                vill.getLookControl().setLookAt(target, 30.0f, 30.0f);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatDirector.tickAttack failed (soft): {}", t.toString());
        }
    }

    public static void stop(Villager vill) {
        try {
            if (vill == null) return;
            State st = STATE.get(vill);
            if (st != null) {
                if (st.eating) finishEating(vill, st);
            }
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Swing control (interrupt)
    // -------------------------------------------------------------------------

    private static void performSwing(Villager vill, LivingEntity target, State st, long now, String reason) {
        try {
            if (vill == null || target == null || st == null) return;

            // Stop blocking before swing
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}

            // Try swing + damage
            doSwingAndHit(vill, target, st, now);

            // After swing: do not block for the duration of the visible swing anim.
            st.noBlockUntil = now + Math.max(1L, SWING_ANIM_TICKS);

            // Reset “hit” trigger for next cycle and restart no-hit timer *after* swing completes.
            st.hitSinceLastSwing = false;
            st.blockNoHitSince = st.noBlockUntil;

            // Throttled info logs
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

            // Vanilla swing (server)
            try { vill.swing(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}

            // deterministic client animation signal
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

    // -------------------------------------------------------------------------
    // Hit detection (Rule #3 trigger)
    // -------------------------------------------------------------------------

    private static void detectHitEdge(Villager vill, State st, long now) {
        try {
            if (vill == null || st == null) return;

            // hurtTime is reset high when a hurt event occurs and counts down.
            int ht = 0;
            try { ht = vill.hurtTime; } catch (Throwable ignored) { ht = 0; }

            // Rising edge: new hit if current hurtTime is greater than last seen.
            if (ht > st.lastHurtTimeSeen) {
                st.lastHitAt = now;
                st.hitSinceLastSwing = true;

                // Reset the no-hit timer immediately (we *did* get hit).
                st.blockNoHitSince = now;

                // Throttled debug/info
                if (st.lastHitLogAt <= 0L || (now - st.lastHitLogAt) >= 10L) {
                    st.lastHitLogAt = now;
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat HIT detected (villager={} hurtTime={} now={})",
                            vill.getUUID(), ht, now);
                }
            }

            st.lastHurtTimeSeen = ht;

        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Blocking control (default state)
    // -------------------------------------------------------------------------

    /**
     * Start blocking if holding a shield in either hand.
     * @return true if we successfully started blocking this call (or were already blocking)
     */
    private static boolean startBlocking(Villager vill) {
        try {
            if (vill == null) return false;

            // If already using item, don't spam startUsingItem.
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

    // -------------------------------------------------------------------------
    // Eating logic (unchanged)
    // -------------------------------------------------------------------------

    private static boolean shouldEat(Villager vill) {
        try {
            return vill.getHealth() <= (vill.getMaxHealth() * 0.5f);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryStartEating(Villager vill, State st) {
        try {
            Container inv = getVillagerInventory(vill);
            if (inv == null) return false;

            int slot = findFoodSlot(inv);
            if (slot < 0) return false;

            ItemStack food = inv.getItem(slot);
            if (food == null || food.isEmpty()) return false;

            st.prevMainhand = vill.getMainHandItem().copy();
            st.prevSlot = slot;
            st.eating = true;

            inv.setItem(slot, ItemStack.EMPTY);
            vill.setItemInHand(InteractionHand.MAIN_HAND, food);
            vill.startUsingItem(InteractionHand.MAIN_HAND);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat eat start (villager={} food={})",
                    safeUuid(vill), safeItemId(food));
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void finishEating(Villager vill, State st) {
        try {
            ItemStack cur = vill.getMainHandItem();
            Container inv = getVillagerInventory(vill);

            if (inv != null && !cur.isEmpty()) {
                inv.setItem(st.prevSlot, cur);
            }

            if (!st.prevMainhand.isEmpty()) {
                vill.setItemInHand(InteractionHand.MAIN_HAND, st.prevMainhand);
            }

            st.prevMainhand = ItemStack.EMPTY;
            st.prevSlot = -1;
            st.eating = false;

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat eat end (villager={})", safeUuid(vill));
        } catch (Throwable ignored) {}
    }

    private static void keepDistanceWhileEating(Villager vill, LivingEntity target, double minDist) {
        try {
            Vec3 villPos = vill.position();
            Vec3 targetPos = target.position();
            double dist = vill.distanceTo(target);
            if (dist >= minDist) {
                vill.getNavigation().stop();
                return;
            }

            Vec3 away = villPos.subtract(targetPos);
            if (away.lengthSqr() < 0.0001) away = new Vec3(1.0, 0.0, 0.0);
            Vec3 dir = away.normalize();
            Vec3 dest = villPos.add(dir.scale(EAT_RETREAT_DIST));
            vill.getNavigation().moveTo(dest.x, dest.y, dest.z, MOVE_SPEED);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private static int findFoodSlot(Container inv) {
        try {
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                if (st.has(DataComponents.FOOD)) return i;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Container getVillagerInventory(Villager vill) {
        try {
            Method m = null;
            Class<?> c = vill.getClass();
            while (c != null && c != Object.class) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm == null) continue;
                    if (!"getInventory".equals(mm.getName())) continue;
                    if (mm.getParameterCount() != 0) continue;
                    mm.setAccessible(true);
                    m = mm;
                    break;
                }
                if (m != null) break;
                c = c.getSuperclass();
            }
            if (m != null) {
                Object out = m.invoke(vill);
                if (out instanceof Container cont) return cont;
            }

            Class<?> c2 = vill.getClass();
            while (c2 != null && c2 != Object.class) {
                for (Field f : c2.getDeclaredFields()) {
                    try {
                        if (f == null) continue;
                        f.setAccessible(true);
                        Object v = f.get(vill);
                        if (v instanceof Container cont) return cont;
                    } catch (Throwable ignored) {}
                }
                c2 = c2.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

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

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private static final class State {
        java.util.UUID targetId;

        long nextSwingAt = 0L;
        long lastSwingAt = -9999L;

        // Do not block until this tick (used to let swing anim complete)
        long noBlockUntil = 0L;

        // “Got hit” trigger
        boolean hitSinceLastSwing = false;
        long lastHitAt = -1L;

        // Tracks hurtTime for rising edge detection
        int lastHurtTimeSeen = 0;

        // “No hit while blocking” timer
        long blockNoHitSince = -1L;

        // Throttled logs
        long lastHitLogAt = 0L;
        long lastSwingReasonLogAt = 0L;

        // Eating
        boolean eating = false;
        int prevSlot = -1;
        ItemStack prevMainhand = ItemStack.EMPTY;
    }
}
