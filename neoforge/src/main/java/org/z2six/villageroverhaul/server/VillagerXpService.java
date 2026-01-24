package org.z2six.villageroverhaul.server;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.logic.HoarderOffers;

import java.lang.reflect.Method;

public final class VillagerXpService {

    private VillagerXpService() {}

    /**
     * Adds villager XP and triggers vanilla level-up flow if eligible.
     * Returns the amount actually applied (0 if it failed).
     */
    public static int grantXp(Villager vill, int add, ServerPlayer maybePlayerForSync) {
        try {
            if (vill == null) return 0;
            if (add <= 0) return 0;

            int lvlBefore = 0;
            int xpBefore = 0;
            try { lvlBefore = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            try { xpBefore = vill.getVillagerXp(); } catch (Throwable ignored) {}

            boolean ok = addVillagerXpSafe(vill, add);
            boolean scheduledVanillaLevelUp = false;
            if (ok) {
                scheduledVanillaLevelUp = maybeInvokeVanillaLevelUpFlow(vill);
                emitHappyParticles(vill);
            }

            // Level-up can append/replace offers; normalize best-effort.
            try { HoarderOffers.normalizeOffers(vill, maybePlayerForSync); } catch (Throwable ignored) {}
            try { VillagerGenerosityOfferService.normalizeAndApply(vill); } catch (Throwable ignored) {}

            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                int lvlAfter = lvlBefore;
                int xpAfter = xpBefore;
                try { lvlAfter = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
                try { xpAfter = vill.getVillagerXp(); } catch (Throwable ignored) {}

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerXpService.grantXp villager={} add={} ok={} vanillaLevelUp={} level {}->{} xp {}->{}",
                        vill.getUUID(), add, ok, scheduledVanillaLevelUp, lvlBefore, lvlAfter, xpBefore, xpAfter);
            }

            return ok ? add : 0;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerXpService.grantXp failed (soft): {}", t.toString());
            return 0;
        }
    }

    private static void emitHappyParticles(Villager vill) {
        try {
            if (vill == null) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            sl.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    vill.getX(), vill.getY() + 1.0, vill.getZ(),
                    6,
                    0.35, 0.35, 0.35,
                    0.0
            );
        } catch (Throwable ignored) {}
    }

    /**
     * Vanilla-accurate level-up trigger:
     * - call Villager.shouldIncreaseLevel() (private)
     * - if true, call Villager.increaseMerchantCareer() (private)
     *
     * IMPORTANT: Do NOT call updateTrades() here.
     */
    private static boolean maybeInvokeVanillaLevelUpFlow(Villager vill) {
        try {
            if (vill == null) return false;

            int lvl = 0;
            try { lvl = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            if (lvl >= 5) return false;

            boolean should = tryInvokeBooleanNoArgMethodAnyVisibility(vill, "shouldIncreaseLevel");
            if (!should) should = tryInvokeBooleanNoArgMethodAnyVisibility(vill, "canLevelUp");
            if (!should) return false;

            if (tryInvokeNoArgMethodAnyVisibility(vill, "increaseMerchantCareer")) return true;
            if (tryInvokeNoArgMethodAnyVisibility(vill, "levelUp")) return true;
            if (tryInvokeNoArgMethodAnyVisibility(vill, "increaseProfessionLevel")) return true;

            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerXpService.maybeInvokeVanillaLevelUpFlow failed (soft): {}", t.toString());
            return false;
        }
    }

    private static boolean tryInvokeBooleanNoArgMethodAnyVisibility(Object target, String name) {
        try {
            if (target == null || name == null) return false;

            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    Object r = m.invoke(target);
                    return (r instanceof Boolean b) && b;
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method m2 = c.getMethod(name);
                        Object r2 = m2.invoke(target);
                        return (r2 instanceof Boolean b2) && b2;
                    } catch (NoSuchMethodException ignored2) {
                        // keep walking
                    }
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryInvokeNoArgMethodAnyVisibility(Object target, String name) {
        try {
            if (target == null || name == null) return false;

            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    m.invoke(target);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method m2 = c.getMethod(name);
                        m2.invoke(target);
                        return true;
                    } catch (NoSuchMethodException ignored2) {
                        // keep walking
                    }
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Try multiple method names across mappings/versions without crashing:
     * - addVillagerXp(int)
     * - addXp(int)
     * - setVillagerXp(getVillagerXp()+x)
     */
    private static boolean addVillagerXpSafe(Villager vill, int add) {
        try {
            if (vill == null) return false;
            if (add <= 0) return true;

            try {
                Method m = vill.getClass().getMethod("addVillagerXp", int.class);
                m.invoke(vill, add);
                return true;
            } catch (Throwable ignored) {}

            try {
                Method m = vill.getClass().getMethod("addXp", int.class);
                m.invoke(vill, add);
                return true;
            } catch (Throwable ignored) {}

            try {
                int cur = 0;
                try { cur = vill.getVillagerXp(); } catch (Throwable ignored2) { cur = 0; }

                long next = (long) cur + (long) add;
                if (next < 0L) next = 0L;
                if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;

                Method m = vill.getClass().getMethod("setVillagerXp", int.class);
                m.invoke(vill, (int) next);
                return true;
            } catch (Throwable ignored) {}

            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}

