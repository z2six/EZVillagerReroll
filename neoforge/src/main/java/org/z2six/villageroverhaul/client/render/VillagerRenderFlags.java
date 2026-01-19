// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/render/VillagerRenderFlags.java
package org.z2six.villageroverhaul.render;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;

/**
 * Server-computed, client-consumed render decisions for villagers.
 *
 * Bits:
 *  - 0x01 => render bodywear (robe)
 *  - 0x02 => render custom humanoid arms (and hide vanilla crossed-arms bone)
 *  - 0x04 => HIDE villager hat part (root.head.hat) (used when helmet is equipped)
 *  - 0x08 => render eating arm pose (manual override; server-authoritative)
 *
 * Derived decisions:
 *  - Vanilla crossed-arms chest-held-item layer should render IFF we are NOT rendering custom arms.
 */
public final class VillagerRenderFlags {

    private VillagerRenderFlags() {}

    public static final byte FLAG_RENDER_BODYWEAR = 0x01;
    public static final byte FLAG_RENDER_CUSTOM_ARMS = 0x02;

    /** If set, the client should NOT render root.head.hat. */
    public static final byte FLAG_HIDE_HAT = 0x04;

    /** If set, the client should force an "eating" arm pose. */
    public static final byte FLAG_EATING_POSE = 0x08;

    /**
     * Default before the server has ticked/synced:
     * - show robe
     * - do NOT show custom arms (keep vanilla crossed arms)
     * - do NOT hide hat
     */
    public static byte defaultFlags() {
        return FLAG_RENDER_BODYWEAR;
    }

    public static boolean renderBodywear(byte flags) {
        return (flags & FLAG_RENDER_BODYWEAR) != 0;
    }

    public static boolean renderCustomArms(byte flags) {
        return (flags & FLAG_RENDER_CUSTOM_ARMS) != 0;
    }

    public static boolean renderEatingPose(byte flags) {
        return (flags & FLAG_EATING_POSE) != 0;
    }

    /** True when the hat should be rendered. */
    public static boolean renderHat(byte flags) {
        return (flags & FLAG_HIDE_HAT) == 0;
    }

    /**
     * Vanilla crossed-arms item layer (the chest-held item render) should render
     * ONLY when we are using vanilla crossed arms (i.e., NOT using custom humanoid arms).
     */
    public static boolean renderVanillaCrossedArmsItemLayer(byte flags) {
        return !renderCustomArms(flags);
    }

    public static byte pack(boolean renderBodywear, boolean renderCustomArms, boolean hideHat) {
        byte out = 0;
        if (renderBodywear) out |= FLAG_RENDER_BODYWEAR;
        if (renderCustomArms) out |= FLAG_RENDER_CUSTOM_ARMS;
        if (hideHat) out |= FLAG_HIDE_HAT;
        return out;
    }

    /**
     * The authoritative logic (A/B/C/D) plus hat hide:
     * - A/B: robe/bodywear only when NO chest AND NO legs item
     * - C/D: custom arms when MAIN or OFF hand has item
     * - NEW: hide hat when HEAD slot has an item (helmet)
     *
     * Intended to be called ONLY by server-side VillagerBrain tick logic.
     */
    public static byte computeFromEquipment(Villager vill) {
        try {
            if (vill == null) return defaultFlags();

            // A/B: robe/bodywear only when NO chest AND NO legs item
            ItemStack chest = safeGetBySlot(vill, EquipmentSlot.CHEST);
            ItemStack legs = safeGetBySlot(vill, EquipmentSlot.LEGS);

            boolean hasChest = chest != null && !chest.isEmpty();
            boolean hasLegs = legs != null && !legs.isEmpty();

            boolean shouldRenderBodywear = !(hasChest || hasLegs);

            // C/D: custom arms when MAIN or OFF hand has item
            ItemStack main = vill.getMainHandItem();
            ItemStack off  = vill.getOffhandItem();

            boolean hasMain = main != null && !main.isEmpty();
            boolean hasOff = off != null && !off.isEmpty();

            boolean shouldRenderCustomArms = (hasMain || hasOff);

            // NEW: hide hat when helmet equipped
            ItemStack head = safeGetBySlot(vill, EquipmentSlot.HEAD);
            boolean hasHelmet = head != null && !head.isEmpty();
            boolean hideHat = hasHelmet;

            return pack(shouldRenderBodywear, shouldRenderCustomArms, hideHat);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerRenderFlags.computeFromEquipment failed (soft): {}", t.toString());
            return defaultFlags();
        }
    }

    private static ItemStack safeGetBySlot(LivingEntity le, EquipmentSlot slot) {
        try {
            if (le == null || slot == null) return ItemStack.EMPTY;
            ItemStack st = le.getItemBySlot(slot);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }
}
