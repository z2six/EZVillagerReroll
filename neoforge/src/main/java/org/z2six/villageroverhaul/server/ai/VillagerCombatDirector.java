// neoforge\src\main\java\org\z2six\villageroverhaul\server\ai\VillagerCombatDirector.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

public final class VillagerCombatDirector {

    private static final double MOVE_SPEED = 0.70;
    private static final double BASE_REACH = 2.0;
    private static final double SAFETY_MARGIN = 0.5;
    private static final long SWING_COOLDOWN_TICKS = 15L;
    private static final long BLOCK_WINDOW_TICKS = 12L;
    private static final long BLOCK_COOLDOWN_TICKS = 6L;
    private static final long BLOCK_AFTER_SWING_DELAY_TICKS = 3L;

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

            if (st.eating) {
                if (!vill.isUsingItem()) {
                    finishEating(vill, st);
                }
            }

            if (!st.eating && shouldEat(vill)) {
                if (tryStartEating(vill, st)) {
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
            if (dist > swingRange) {
                vill.getNavigation().moveTo(target, MOVE_SPEED);
            } else {
                vill.getNavigation().stop();
                trySwing(vill, target, st, now);
            }

            updateBlocking(vill, target, st, now);
            vill.getLookControl().setLookAt(target, 30.0f, 30.0f);

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
            vill.getNavigation().stop();
            vill.stopUsingItem();
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
    }

    private static void trySwing(Villager vill, LivingEntity target, State st, long now) {
        if (now < st.nextSwingAt) return;
        vill.stopUsingItem();
        vill.swing(InteractionHand.MAIN_HAND);
        boolean hit = vill.doHurtTarget(target);
        if (!hit) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat swing dealt no damage (villager={} target={})",
                    vill.getUUID(), target.getUUID());
        }
        st.nextSwingAt = now + SWING_COOLDOWN_TICKS;
        st.lastSwingAt = now;
    }

    private static void updateBlocking(Villager vill, LivingEntity target, State st, long now) {
        if (st.eating) return;

        if ((now - st.lastSwingAt) < BLOCK_AFTER_SWING_DELAY_TICKS) {
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            return;
        }

        if (now < st.blockUntil) {
            if (!vill.isUsingItem()) {
                startBlocking(vill);
            }
            return;
        }

        if (now < st.blockCooldownUntil) {
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            return;
        }

        double dist = vill.distanceTo(target);
        if (dist > 6.0) {
            try { vill.stopUsingItem(); } catch (Throwable ignored) {}
            return;
        }

        st.blockUntil = now + BLOCK_WINDOW_TICKS;
        st.blockCooldownUntil = st.blockUntil + BLOCK_COOLDOWN_TICKS;
        startBlocking(vill);
    }

    private static void startBlocking(Villager vill) {
        if (vill.isUsingItem()) return;
        ItemStack off = vill.getOffhandItem();
        if (isShieldItem(off)) {
            vill.startUsingItem(InteractionHand.OFF_HAND);
            return;
        }
        ItemStack main = vill.getMainHandItem();
        if (isShieldItem(main)) {
            vill.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

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
                    vill.getUUID(), safeItemId(food));
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

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat eat end (villager={})", vill.getUUID());
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
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static final class State {
        java.util.UUID targetId;
        long nextSwingAt = 0L;
        long lastSwingAt = -9999L;
        long blockUntil = 0L;
        long blockCooldownUntil = 0L;
        boolean eating = false;
        int prevSlot = -1;
        ItemStack prevMainhand = ItemStack.EMPTY;
    }
}
