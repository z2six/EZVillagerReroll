package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.menu.VillagerInventoryMenu;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Debug-only service: forces a villager to consume a food item from its 8-slot pickup inventory.
 *
 * Goal: validate that we can drive vanilla consume (finishUsingItem) and observe health changes.
 */
public final class VillagerEatTestService {
    private static final Map<Villager, State> STATE = new WeakHashMap<>();

    private static volatile Method FINISH_USING_ITEM = null;
    private static volatile boolean FINISH_SCANNED = false;

    private static volatile Method GET_USE_DURATION_0 = null;
    private static volatile Method GET_USE_DURATION_1 = null;
    private static volatile boolean USE_DUR_SCANNED = false;

    private VillagerEatTestService() {}

    public static boolean requestEatNearestFood(Villager vill) {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel sl)) return false;
            if (STATE.containsKey(vill)) return false;

            Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
            if (inv == null) return false;

            int foodSlot = -1;
            ItemStack foodStack = ItemStack.EMPTY;
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                if (st.get(DataComponents.FOOD) == null) continue;
                if (st.getUseAnimation() != UseAnim.EAT) continue;
                foodSlot = i;
                foodStack = st;
                break;
            }
            if (foodSlot < 0 || foodStack.isEmpty()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} no_food_found", vill.getUUID());
                return false;
            }

            int useDuration = safeUseDuration(foodStack, vill);
            if (useDuration <= 0) useDuration = 32;

            // Pause other AI enforcement while we run the test.
            VillagerBrain.setUiPaused(vill, true);

            // Move 1 food item from pickup inv into main hand (real stack transfer, no duplication).
            ItemStack one = foodStack.copy();
            one.setCount(1);

            ItemStack remaining = foodStack.copy();
            remaining.shrink(1);
            inv.setItem(foodSlot, remaining);
            inv.setChanged();

            // Save current mainhand so we can restore it after eating.
            ItemStack prevMain = vill.getMainHandItem();
            if (prevMain == null) prevMain = ItemStack.EMPTY;

            // Clear hand (allow-clear mixin), then equip food and start using it.
            allowClearHand(vill, InteractionHand.MAIN_HAND, "eat_test_start");
            vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            vill.setItemInHand(InteractionHand.MAIN_HAND, one.copy());
            vill.startUsingItem(InteractionHand.MAIN_HAND);

            long now = sl.getGameTime();
            State st = new State();
            st.startedAt = now;
            st.finishAt = now + Math.max(1, useDuration);
            st.pickupInv = inv;
            st.prevMain = prevMain.copy();
            st.foodUsed = one.copy();
            st.foodName = safeItem(one);
            st.hpBefore = vill.getHealth();
            STATE.put(vill, st);

            try {
                // Used by VillagerBrain.tickRenderDecisions to force an eating pose on the client.
                vill.getPersistentData().putLong("ezvr_eat_pose_until", st.finishAt);
            } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} action=start item={} durationTicks={} hpBefore={}",
                    vill.getUUID(), st.foodName, useDuration, trim1(st.hpBefore));
            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] [eat_test] requestEatNearestFood failed", t);
            return false;
        }
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (STATE.isEmpty()) return;

            Iterator<Map.Entry<Villager, State>> it = STATE.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Villager vill = e.getKey();
                State st = e.getValue();
                if (vill == null || st == null || vill.level() == null || vill.level().isClientSide() || !vill.isAlive()) {
                    it.remove();
                    continue;
                }
                if (!(vill.level() instanceof ServerLevel sl)) {
                    it.remove();
                    continue;
                }

                long now = sl.getGameTime();
                if (now < st.finishAt) continue;

                // Finish using.
                ItemStack hand = vill.getMainHandItem();
                if (hand == null) hand = ItemStack.EMPTY;

                float hpMid = vill.getHealth();

                // Try to spawn eat particles (vanilla client handler typically listens for entity event 9).
                try {
                    vill.level().broadcastEntityEvent(vill, (byte) 9);
                } catch (Throwable ignored) {}

                ItemStack result = tryFinishUsingItem(hand, vill);
                if (result == null) result = ItemStack.EMPTY;

                // Option A test: vanilla consume did not heal villagers, so apply a predictable heal based on FOOD nutrition.
                float heal = computeFoodHealFromStack(st.foodUsed);
                if (heal > 0.0f) {
                    float beforeHeal = vill.getHealth();
                    try { vill.heal(heal); } catch (Throwable ignored) {}
                    float afterHeal = vill.getHealth();
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} action=heal food={} heal={} hp {}->{}",
                            vill.getUUID(), safeItem(st.foodUsed), trim1(heal), trim1(beforeHeal), trim1(afterHeal));
                } else {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} action=heal_skip food={} (no_food_component?)",
                            vill.getUUID(), safeItem(st.foodUsed));
                }

                // Clear food hand and restore previous main hand item.
                allowClearHand(vill, InteractionHand.MAIN_HAND, "eat_test_finish");
                vill.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                if (st.prevMain != null && !st.prevMain.isEmpty()) {
                    vill.setItemInHand(InteractionHand.MAIN_HAND, st.prevMain.copy());
                }

                // Store remainder (e.g., bowl) if any.
                if (!result.isEmpty()) {
                    storeOrDropToPickup(vill, st.pickupInv, result.copy(), "remainder");
                }

                // Unpause.
                VillagerBrain.setUiPaused(vill, false);

                float hpAfter = vill.getHealth();
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} action=finish item={} hp {}->{} (mid={}) remainder={}",
                        vill.getUUID(),
                        safe(st.foodName),
                        trim1(st.hpBefore),
                        trim1(hpAfter),
                        trim1(hpMid),
                        safeItem(result));

                try {
                    vill.getPersistentData().remove("ezvr_eat_pose_until");
                } catch (Throwable ignored) {}

                it.remove();
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] [eat_test] tick failed", t);
        }
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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] finishUsingItem reflection failed (soft): {}", t.toString());
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

    private static void storeOrDropToPickup(Villager vill, Container inv, ItemStack stack, String why) {
        try {
            if (vill == null || inv == null) return;
            if (stack == null || stack.isEmpty()) return;

            ItemStack remaining = stack.copy();

            int size = inv.getContainerSize();
            for (int i = 0; i < size && !remaining.isEmpty(); i++) {
                ItemStack slot = inv.getItem(i);
                if (slot == null || slot.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(slot, remaining)) continue;

                int max = Math.min(slot.getMaxStackSize(), inv.getMaxStackSize());
                int can = Math.max(0, max - slot.getCount());
                if (can <= 0) continue;

                int move = Math.min(can, remaining.getCount());
                if (move <= 0) continue;

                slot.grow(move);
                remaining.shrink(move);
                inv.setChanged();
            }

            for (int i = 0; i < size && !remaining.isEmpty(); i++) {
                ItemStack slot = inv.getItem(i);
                if (slot != null && !slot.isEmpty()) continue;

                int max = Math.min(remaining.getMaxStackSize(), inv.getMaxStackSize());
                ItemStack toPut = remaining.copy();
                if (toPut.getCount() > max) toPut.setCount(max);

                inv.setItem(i, toPut);
                remaining.shrink(toPut.getCount());
                inv.setChanged();
            }

            if (!remaining.isEmpty()) {
                try { vill.spawnAtLocation(remaining.copy()); } catch (Throwable ignored) {}
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} action=drop why={} item={}",
                        vill.getUUID(), safe(why), safeItem(remaining));
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [eat_test] villager={} action=store why={} item={}",
                        vill.getUUID(), safe(why), safeItem(stack));
            }

        } catch (Throwable ignored) {}
    }

    private static String safe(Object o) {
        try { return o == null ? "null" : String.valueOf(o); } catch (Throwable ignored) { return "err"; }
    }

    private static float computeFoodHealFromStack(ItemStack foodStack) {
        try {
            if (foodStack == null || foodStack.isEmpty()) return 0.0f;
            Object food = foodStack.get(DataComponents.FOOD);
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

            // Debug-friendly: 1 nutrition = 1 HP.
            // We can tune later (or use saturation modifier) once we hook into combat logic.
            float heal = (float) nutrition;

            // Small bonus for high saturation foods (optional, bounded).
            if (satMod > 0.0f) {
                heal += Math.min(4.0f, satMod * 2.0f);
            }

            return Math.max(0.0f, heal);
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private static String safeItem(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "empty";
            String id = "unknown";
            try { id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem())); } catch (Throwable ignored) {}
            int n = 0;
            try { n = st.getCount(); } catch (Throwable ignored) {}
            return n + "x" + id;
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String trim1(float f) {
        try { return String.format(java.util.Locale.ROOT, "%.1f", f); } catch (Throwable ignored) { return String.valueOf(f); }
    }

    private static final class State {
        long startedAt;
        long finishAt;
        Container pickupInv;
        ItemStack prevMain;
        ItemStack foodUsed;
        String foodName;
        float hpBefore;
    }
}
