// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/CombatBlockDiagnostics.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side diagnostics to determine whether vanilla is actually treating our villager as blocking.
 *
 * We log:
 * - LivingIncomingDamageEvent: always, before damage is applied (source, attacker, amount)
 * - LivingShieldBlockEvent: only when vanilla checks shield block (blocked amount, and "original" if accessible)
 *
 * Notes:
 * - Method names differ across NeoForge/MC versions, so we avoid hard references to optional getters.
 * - This class is intentionally fail-soft: no crashes, just logs.
 */
public final class CombatBlockDiagnostics {

    private static volatile boolean registered = false;

    // Throttle logs per victim so it doesn't spam endlessly.
    private static final Map<UUID, Long> LAST_LOG_TICK = new HashMap<>();
    private static final long LOG_INTERVAL_TICKS = 10L;

    // Reflection helpers (best-effort) for identifying damage type / msg id
    private static volatile Method DAMAGE_SOURCE_TYPE; // DamageSource#type() (or equivalent)
    private static volatile Method DAMAGE_TYPE_MSGID;  // DamageType#msgId()

    private CombatBlockDiagnostics() {}

    public static void register() {
        if (registered) return;
        registered = true;

        NeoForge.EVENT_BUS.addListener(CombatBlockDiagnostics::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(CombatBlockDiagnostics::onShieldBlock);

        VillagerOverhaul.LOG().info("[VillagerOverhaul] CombatBlockDiagnostics registered on NeoForge EVENT_BUS.");
        warmupDamageTypeReflection();
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent e) {
        try {
            if (e == null) return;

            LivingEntity victim = e.getEntity();
            if (!(victim instanceof Villager vill)) return;

            // Only care about your recruited/combat-driven villagers (avoid global spam).
            if (!shouldLogFor(vill)) return;

            long now = vill.level() == null ? 0L : vill.level().getGameTime();
            if (!throttle(vill.getUUID(), now)) return;

            DamageSource src = e.getSource();
            Entity attacker = src == null ? null : src.getEntity();

            String srcId = describeDamageSource(src);
            String attackerId = describeEntity(attacker);

            boolean using = safeIsUsing(vill);
            InteractionHand usedHand = safeUsedHand(vill);
            ItemStack useItem = safeUseItem(vill);

            ItemStack main = safeMain(vill);
            ItemStack off = safeOff(vill);

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] [blockdiag] INCOMING victim={} mode={} engaged={} using={} usedHand={} useItem={} main={} off={} attacker={} src={} amount={}",
                    vill.getUUID(),
                    safeMode(vill),
                    VillagerBrain.isCombatEngaged(vill),
                    using,
                    String.valueOf(usedHand),
                    safeItem(useItem),
                    safeItem(main),
                    safeItem(off),
                    attackerId,
                    srcId,
                    fmt2(e.getAmount())
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatBlockDiagnostics.onIncomingDamage failed (soft): {}", t.toString());
        }
    }

    private static void onShieldBlock(LivingShieldBlockEvent e) {
        try {
            if (e == null) return;

            LivingEntity victim = e.getEntity();
            if (!(victim instanceof Villager vill)) return;

            if (!shouldLogFor(vill)) return;

            long now = vill.level() == null ? 0L : vill.level().getGameTime();
            if (!throttle(vill.getUUID(), now)) return;

            DamageSource src = e.getDamageSource();
            Entity attacker = src == null ? null : src.getEntity();

            String srcId = describeDamageSource(src);
            String attackerId = describeEntity(attacker);

            boolean using = safeIsUsing(vill);
            InteractionHand usedHand = safeUsedHand(vill);
            ItemStack useItem = safeUseItem(vill);

            // Best-effort "original" damage value (method names vary by version).
            // If not available, we log -1.0 to indicate unknown.
            float original = tryGetOriginalDamageBestEffort(e);

            // getBlockedDamage() exists on your version (you compiled against it).
            float blocked = safeFloat(() -> e.getBlockedDamage());

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul.CombatBlockDiagnostics] [blockdiag] SHIELD_CHECK victim={} using={} usedHand={} useItem={} attacker={} src={} original={} blocked={} cancel={}",
                    vill.getUUID(),
                    using,
                    String.valueOf(usedHand),
                    safeItem(useItem),
                    attackerId,
                    srcId,
                    fmt2(original),
                    fmt2(blocked),
                    e.isCanceled()
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatBlockDiagnostics.onShieldBlock failed (soft): {}", t.toString());
        }
    }

    private static boolean shouldLogFor(Villager vill) {
        try {
            if (vill == null || vill.level() == null || vill.level().isClientSide()) return false;

            // Only log for controllable villagers / active combat contexts.
            return VillagerBrain.shouldCombatActNow(vill) || VillagerBrain.isCombatEngaged(vill);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean throttle(UUID id, long now) {
        try {
            if (id == null) return false;
            Long last = LAST_LOG_TICK.get(id);
            if (last != null && (now - last) < LOG_INTERVAL_TICKS) return false;
            LAST_LOG_TICK.put(id, now);
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean safeIsUsing(Villager v) {
        try { return v != null && v.isUsingItem(); } catch (Throwable ignored) { return false; }
    }

    private static InteractionHand safeUsedHand(Villager v) {
        try { return v == null ? null : v.getUsedItemHand(); } catch (Throwable ignored) { return null; }
    }

    private static ItemStack safeUseItem(Villager v) {
        try { return v == null ? ItemStack.EMPTY : v.getUseItem(); } catch (Throwable ignored) { return ItemStack.EMPTY; }
    }

    private static ItemStack safeMain(Villager v) {
        try { return v == null ? ItemStack.EMPTY : v.getMainHandItem(); } catch (Throwable ignored) { return ItemStack.EMPTY; }
    }

    private static ItemStack safeOff(Villager v) {
        try { return v == null ? ItemStack.EMPTY : v.getOffhandItem(); } catch (Throwable ignored) { return ItemStack.EMPTY; }
    }

    private static String safeMode(Villager v) {
        try { return v == null ? "null" : VillagerBrain.getCombatMode(v).id; } catch (Throwable ignored) { return "unknown"; }
    }

    private static String safeItem(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "empty";
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            return id == null ? String.valueOf(st.getItem()) : id.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String describeEntity(Entity e) {
        try {
            if (e == null) return "null";
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return (id == null ? e.getType().toString() : id.toString()) + "@" + e.getUUID();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String describeDamageSource(DamageSource src) {
        try {
            if (src == null) return "null";
            String msg = tryDamageMsgId(src);
            if (msg != null && !msg.isBlank()) return msg;
            return src.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static void warmupDamageTypeReflection() {
        try {
            for (Method m : DamageSource.class.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!"type".equals(m.getName())) continue;
                DAMAGE_SOURCE_TYPE = m;
                break;
            }
        } catch (Throwable ignored) {}
    }

    private static String tryDamageMsgId(DamageSource src) {
        try {
            if (src == null) return null;
            if (DAMAGE_SOURCE_TYPE == null) return null;

            Object typeOrHolder = DAMAGE_SOURCE_TYPE.invoke(src);
            if (typeOrHolder == null) return null;

            Object damageType = typeOrHolder;

            // If it's a Holder, try holder.value()
            try {
                Method value = typeOrHolder.getClass().getMethod("value");
                damageType = value.invoke(typeOrHolder);
            } catch (Throwable ignored) {}

            if (damageType == null) return null;

            // Try msgId()
            try {
                if (DAMAGE_TYPE_MSGID == null) {
                    DAMAGE_TYPE_MSGID = damageType.getClass().getMethod("msgId");
                }
                Object v = DAMAGE_TYPE_MSGID.invoke(damageType);
                if (v instanceof String s) return s;
            } catch (Throwable ignored) {}

            return damageType.getClass().getName();

        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Best-effort: different NeoForge versions expose "original" damage on this event under different names.
     * If none found, returns -1.0f.
     */
    private static float tryGetOriginalDamageBestEffort(LivingShieldBlockEvent e) {
        try {
            if (e == null) return -1.0f;

            // Try a few known / plausible method names.
            Float v;

            v = tryInvokeFloatNoArg(e, "getOriginalDamage");
            if (v != null) return v;

            v = tryInvokeFloatNoArg(e, "getOriginal");
            if (v != null) return v;

            v = tryInvokeFloatNoArg(e, "getDamage");
            if (v != null) return v;

            v = tryInvokeFloatNoArg(e, "getAmount");
            if (v != null) return v;

            v = tryInvokeFloatNoArg(e, "getShieldDamage");
            if (v != null) return v;

            // Last resort: scan methods for something that looks like "original" and returns float.
            for (Method m : e.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt != float.class && rt != Float.class) continue;

                String n = m.getName();
                if (n == null) continue;
                String nl = n.toLowerCase();

                if (nl.contains("original") && nl.contains("damage")) {
                    Object out = m.invoke(e);
                    if (out instanceof Float f) return f;
                    if (out instanceof Number num) return num.floatValue();
                }
            }

        } catch (Throwable ignored) {}

        return -1.0f;
    }

    private static Float tryInvokeFloatNoArg(Object inst, String methodName) {
        try {
            if (inst == null || methodName == null) return null;
            Method m = inst.getClass().getMethod(methodName);
            Object out = m.invoke(inst);
            if (out instanceof Float f) return f;
            if (out instanceof Number n) return n.floatValue();
        } catch (Throwable ignored) {}
        return null;
    }

    private interface FloatSupplier {
        float get() throws Exception;
    }

    private static float safeFloat(FloatSupplier s) {
        try { return s == null ? -1.0f : s.get(); } catch (Throwable ignored) { return -1.0f; }
    }

    private static String fmt2(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) return "0";
        return String.valueOf(Math.round(f * 100.0f) / 100.0f);
    }
}
