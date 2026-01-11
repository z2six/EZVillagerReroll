// MainFile: src/main/java/org/z2six/villageroverhaul/logic/MoneyBridge.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class MoneyBridge {

    private static volatile boolean lookedUp = false;
    private static volatile boolean lcPresent = false;

    private static Class<?> clsMoneyAPI;
    private static Class<?> clsIMoneyHandler;
    private static Class<?> clsMoneyValue;
    private static Class<?> clsCoinValue;

    private static Method mMoneyAPI_getPlayersMoneyHandler;
    private static Method mIMoneyHandler_extractMoney;
    private static Method mMoneyValue_isEmpty;
    private static Method mCoinValue_fromItem_long;
    private static Method mCoinValue_fromItem_int;
    private static Method mCoinValue_fromStack_long;
    private static Method mCoinValue_fromStack_int;

    private static Object moneyApiTarget;
    private static Object moneyApiCompanion;

    public static boolean isLCPresent() {
        if (!lookedUp) {
            lcPresent = ModList.get().isLoaded("lightmanscurrency");
            lookedUp = true;
            if (lcPresent) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] LC present: binding MoneyAPI (factory path)...");
                bind();
            } else {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] LC not present: MoneyAPI disabled.");
            }
        }
        if (lcPresent && !areBound()) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI not fully bound; retrying bind...");
            bind();
            if (!areBound()) VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI still not fully bound after retry.");
        }
        return lcPresent;
    }

    public static boolean canAfford(ServerPlayer player, ResourceLocation coinId, int count) {
        try {
            if (!isLCPresent() || !areBound()) return false;
            if (coinId == null || count <= 0) return true;
            Item coinItem = BuiltInRegistries.ITEM.get(coinId);
            if (coinItem == null) return false;

            Object moneyValue = buildMoneyValue(coinItem, count);
            if (moneyValue == null) return false;
            Object handler = invokeHandlerGetter(player);
            if (handler == null) return false;

            Object remainderSim = mIMoneyHandler_extractMoney.invoke(handler, moneyValue, Boolean.TRUE);
            return isMoneyEmpty(remainderSim);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI: canAfford simulate failed: {}", t.toString());
            return false;
        }
    }

    public static boolean tryExtract(ServerPlayer player, ResourceLocation coinId, int count) {
        try {
            if (!isLCPresent() || !areBound()) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI not bound; falling back.");
                return false;
            }
            if (coinId == null || count <= 0) return true;

            Item coinItem = BuiltInRegistries.ITEM.get(coinId);
            if (coinItem == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI: item '{}' not found.", coinId);
                return false;
            }

            Object moneyValue = buildMoneyValue(coinItem, count);
            if (moneyValue == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI: failed to build MoneyValue for {} x {}", count, coinId);
                return false;
            }

            Object handler = invokeHandlerGetter(player);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] MoneyAPI: handler -> {}", handler == null ? "null" : handler.getClass().getName());
            if (handler == null) return false;

            Object remainderSim = mIMoneyHandler_extractMoney.invoke(handler, moneyValue, Boolean.TRUE);
            boolean simOK = isMoneyEmpty(remainderSim);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] MoneyAPI: simulate isEmpty -> {}", simOK);
            if (!simOK) return false;

            Object remainderReal = mIMoneyHandler_extractMoney.invoke(handler, moneyValue, Boolean.FALSE);
            boolean realOK = isMoneyEmpty(remainderReal);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] MoneyAPI: real isEmpty -> {}", realOK);
            return realOK;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] MoneyAPI extract failed (soft): {}", t.toString());
            return false;
        }
    }

    private static void bind() {
        try {
            clsMoneyAPI = Class.forName("io.github.lightman314.lightmanscurrency.api.money.MoneyAPI");
            clsIMoneyHandler = Class.forName("io.github.lightman314.lightmanscurrency.api.capability.money.IMoneyHandler");
            clsMoneyValue = Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue");
            clsCoinValue = Class.forName("io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue");

            mMoneyAPI_getPlayersMoneyHandler = tryGetHandlerGetter(clsMoneyAPI);

            if (mMoneyAPI_getPlayersMoneyHandler != null && !Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers())) {
                try {
                    Field f = clsMoneyAPI.getDeclaredField("API");
                    f.setAccessible(true);
                    if (Modifier.isStatic(f.getModifiers())) {
                        Object v = f.get(null);
                        if (v != null) {
                            moneyApiTarget = v;
                            VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: using MoneyAPI.API singleton -> {}", v.getClass().getName());
                        }
                    }
                } catch (NoSuchFieldException ignored) {}
                if (moneyApiTarget == null) {
                    resolveMoneyApiInstances(clsMoneyAPI);
                }
            } else if (mMoneyAPI_getPlayersMoneyHandler != null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: handler getter is STATIC");
            }

            mIMoneyHandler_extractMoney = clsIMoneyHandler.getMethod("extractMoney", clsMoneyValue, boolean.class);
            mIMoneyHandler_extractMoney.setAccessible(true);
            VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: IMoneyHandler#extractMoney(...) OK");

            mMoneyValue_isEmpty = clsMoneyValue.getMethod("isEmpty");
            mMoneyValue_isEmpty.setAccessible(true);
            VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: MoneyValue#isEmpty() OK");

            bindCoinValueFactories();

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] MoneyAPI bound check: getter={}, static={}, hasAPIorInstance={}, hasCompanion={}, extractMoney={}, factories(item,long|int; stack,long|int)={}|{};{}|{}, isEmpty={}",
                    (mMoneyAPI_getPlayersMoneyHandler != null),
                    (mMoneyAPI_getPlayersMoneyHandler != null && Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers())),
                    (moneyApiTarget != null),
                    (moneyApiCompanion != null),
                    (mIMoneyHandler_extractMoney != null),
                    (mCoinValue_fromItem_long != null),
                    (mCoinValue_fromItem_int != null),
                    (mCoinValue_fromStack_long != null),
                    (mCoinValue_fromStack_int != null),
                    (mMoneyValue_isEmpty != null)
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] MoneyBridge.bind failed: {}", t.toString());
        }
    }

    private static boolean areBound() {
        boolean handlerOK;
        if (mMoneyAPI_getPlayersMoneyHandler == null) handlerOK = false;
        else if (Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers())) handlerOK = true;
        else handlerOK = (moneyApiTarget != null || moneyApiCompanion != null);

        return clsMoneyAPI != null
                && clsIMoneyHandler != null
                && clsMoneyValue != null
                && clsCoinValue != null
                && handlerOK
                && mIMoneyHandler_extractMoney != null
                && mMoneyValue_isEmpty != null
                && (mCoinValue_fromItem_long != null
                || mCoinValue_fromItem_int != null
                || mCoinValue_fromStack_long != null
                || mCoinValue_fromStack_int != null);
    }

    private static Method tryGetHandlerGetter(Class<?> moneyAPI) {
        String[] names = new String[]{
                "GetPlayersMoneyHandler", "getPlayersMoneyHandler",
                "GetPlayerMoneyHandler", "getPlayerMoneyHandler",
                "GetMoneyHandler", "getMoneyHandler"
        };
        Class<?>[] params = new Class<?>[]{
                Player.class, ServerPlayer.class, LivingEntity.class, Entity.class
        };
        for (String n : names) {
            for (Class<?> p : params) {
                try {
                    Method m = moneyAPI.getMethod(n, p);
                    m.setAccessible(true);
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: MoneyAPI#{}({}) OK", n, p.getSimpleName());
                    return m;
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method m = moneyAPI.getDeclaredMethod(n, p);
                    m.setAccessible(true);
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: MoneyAPI#declared {}({}) OK", n, p.getSimpleName());
                    return m;
                } catch (NoSuchMethodException ignored2) {}
            }
        }
        VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: handler getter not found (tried common names)");
        return null;
    }

    private static void resolveMoneyApiInstances(Class<?> moneyAPI) {
        try {
            if (moneyAPI.isEnum()) {
                Object[] constants = moneyAPI.getEnumConstants();
                if (constants != null && constants.length > 0) {
                    moneyApiTarget = constants[0];
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI bind: using enum constant as instance.");
                    return;
                }
            }
        } catch (Throwable ignored) {}

        for (String fn : new String[]{"INSTANCE", "Instance", "instance"}) {
            try {
                Field f = null;
                try { f = moneyAPI.getField(fn); } catch (NoSuchFieldException ignored) {}
                if (f == null) f = moneyAPI.getDeclaredField(fn);
                f.setAccessible(true);
                if (Modifier.isStatic(f.getModifiers())) {
                    Object v = f.get(null);
                    if (v != null) { moneyApiTarget = v; return; }
                }
            } catch (Throwable ignored) {}
        }

        try {
            Field f = null;
            try { f = moneyAPI.getField("Companion"); } catch (NoSuchFieldException ignored) {}
            if (f == null) f = moneyAPI.getDeclaredField("Companion");
            f.setAccessible(true);
            if (Modifier.isStatic(f.getModifiers())) {
                Object v = f.get(null);
                if (v != null) moneyApiCompanion = v;
            }
        } catch (Throwable ignored) {}

        for (String mn : new String[]{"get", "getInstance", "instance"}) {
            try {
                Method m = null;
                try { m = moneyAPI.getMethod(mn); } catch (NoSuchMethodException ignored) {}
                if (m == null) m = moneyAPI.getDeclaredMethod(mn);
                m.setAccessible(true);
                if (Modifier.isStatic(m.getModifiers())) {
                    Object v = m.invoke(null);
                    if (v != null) { moneyApiTarget = v; return; }
                }
            } catch (Throwable ignored) {}
        }

        try {
            Constructor<?> c = moneyAPI.getDeclaredConstructor();
            c.setAccessible(true);
            Object v = c.newInstance();
            if (v != null) moneyApiTarget = v;
        } catch (Throwable ignored) {}
    }

    private static void bindCoinValueFactories() {
        try {
            for (Method m : clsCoinValue.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (!clsMoneyValue.isAssignableFrom(m.getReturnType())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Item.class && p[1] == long.class) mCoinValue_fromItem_long = m;
                else if (p.length == 2 && p[0] == Item.class && p[1] == int.class) mCoinValue_fromItem_int = m;
                else if (p.length == 2 && p[0] == ItemStack.class && p[1] == long.class) mCoinValue_fromStack_long = m;
                else if (p.length == 2 && p[0] == ItemStack.class && p[1] == int.class) mCoinValue_fromStack_int = m;
            }
            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] MoneyAPI bind: CoinValue factories -> item,long={}, item,int={}, stack,long={}, stack,int={}",
                    (mCoinValue_fromItem_long != null),
                    (mCoinValue_fromItem_int != null),
                    (mCoinValue_fromStack_long != null),
                    (mCoinValue_fromStack_int != null)
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] MoneyAPI bind: scanning CoinValue factories failed: {}", t.toString());
        }
    }

    private static Object buildMoneyValue(Item coinItem, int count) {
        try {
            if (count <= 0) count = 1;
            if (mCoinValue_fromItem_long != null)
                return mCoinValue_fromItem_long.invoke(null, coinItem, (long) count);
            if (mCoinValue_fromItem_int != null)
                return mCoinValue_fromItem_int.invoke(null, coinItem, count);

            ItemStack stack = new ItemStack(coinItem, count);
            if (mCoinValue_fromStack_long != null)
                return mCoinValue_fromStack_long.invoke(null, stack, (long) count);
            if (mCoinValue_fromStack_int != null)
                return mCoinValue_fromStack_int.invoke(null, stack, count);

            return null;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] MoneyAPI: buildMoneyValue failed: {}", t.toString());
            return null;
        }
    }

    private static boolean isMoneyEmpty(Object moneyValue) {
        try {
            if (moneyValue == null) return true;
            Object r = mMoneyValue_isEmpty.invoke(moneyValue);
            return (r instanceof Boolean b) && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object invokeHandlerGetter(ServerPlayer player) {
        try {
            if (mMoneyAPI_getPlayersMoneyHandler == null) return null;

            Class<?> p = mMoneyAPI_getPlayersMoneyHandler.getParameterTypes()[0];
            Object arg = (p == Player.class) ? (Player) player
                    : (p == ServerPlayer.class) ? player
                    : (p == LivingEntity.class) ? (LivingEntity) player
                    : (p == Entity.class) ? (Entity) player
                    : player;

            if (Modifier.isStatic(mMoneyAPI_getPlayersMoneyHandler.getModifiers()))
                return mMoneyAPI_getPlayersMoneyHandler.invoke(null, arg);

            if (moneyApiTarget != null)
                return mMoneyAPI_getPlayersMoneyHandler.invoke(moneyApiTarget, arg);

            if (moneyApiCompanion != null)
                return mMoneyAPI_getPlayersMoneyHandler.invoke(moneyApiCompanion, arg);

            return null;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] MoneyAPI: invoke handler getter failed: {}", t.toString());
            return null;
        }
    }

    private MoneyBridge() {}
}
