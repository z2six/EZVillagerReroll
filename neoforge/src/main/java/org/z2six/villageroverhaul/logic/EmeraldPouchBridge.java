package org.z2six.villageroverhaul.logic;

import net.minecraft.server.level.ServerPlayer;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Method;

public final class EmeraldPouchBridge {

    private static final String API_CLASS = "org.z2six.ezemeraldpouch.api.EZEmeraldPouchApi";

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static Method hasPouchMethod;
    private static Method getStoredMethod;
    private static Method withdrawMethod;
    private static Method giveMethod;

    private EmeraldPouchBridge() {}

    public static boolean isAvailable() {
        resolve();
        return available;
    }

    public static boolean hasPouch(ServerPlayer sp) {
        try {
            if (sp == null) return false;
            resolve();
            if (!available || hasPouchMethod == null) return false;
            Object out = hasPouchMethod.invoke(null, sp);
            return out instanceof Boolean b && b;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] EmeraldPouchBridge.hasPouch failed (soft): {}", t.toString());
            return false;
        }
    }

    public static long getStoredEmeralds(ServerPlayer sp) {
        try {
            if (sp == null) return 0L;
            resolve();
            if (!available || getStoredMethod == null) return 0L;
            Object out = getStoredMethod.invoke(null, sp);
            return out instanceof Number n ? Math.max(0L, n.longValue()) : 0L;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] EmeraldPouchBridge.getStoredEmeralds failed (soft): {}", t.toString());
            return 0L;
        }
    }

    public static long withdraw(ServerPlayer sp, long emeralds) {
        try {
            if (sp == null || emeralds <= 0L) return 0L;
            resolve();
            if (!available || withdrawMethod == null) return 0L;
            Object out = withdrawMethod.invoke(null, sp, emeralds);
            return out instanceof Number n ? Math.max(0L, n.longValue()) : 0L;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] EmeraldPouchBridge.withdraw failed (soft): {}", t.toString());
            return 0L;
        }
    }

    public static boolean give(ServerPlayer sp, long emeralds) {
        try {
            if (sp == null || emeralds <= 0L) return true;
            resolve();
            if (!available || giveMethod == null) return false;
            Object out = giveMethod.invoke(null, sp, emeralds);
            return out instanceof Number n && n.longValue() == emeralds;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] EmeraldPouchBridge.give failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (EmeraldPouchBridge.class) {
            if (resolved) return;
            try {
                Class<?> api = Class.forName(API_CLASS);
                hasPouchMethod = api.getMethod("hasPouch", net.minecraft.world.entity.player.Player.class);
                getStoredMethod = api.getMethod("getStoredEmeralds", net.minecraft.world.entity.player.Player.class);
                withdrawMethod = api.getMethod("withdrawFromPouch", net.minecraft.world.entity.player.Player.class, long.class);
                giveMethod = api.getMethod("giveEmeralds", ServerPlayer.class, long.class);
                available = true;
            } catch (Throwable t) {
                available = false;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] EmeraldPouchBridge unavailable: {}", t.toString());
            } finally {
                resolved = true;
            }
        }
    }
}
