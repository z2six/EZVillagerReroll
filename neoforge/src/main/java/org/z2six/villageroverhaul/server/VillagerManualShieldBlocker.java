// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerManualShieldBlocker.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manual shield block implementation for Villagers.
 *
 * Why:
 * - Vanilla does not run shield-block evaluation (LivingShieldBlockEvent never fires) for Villager vs mob hits.
 * - We still want "real" blocking: negate damage, no hurt FX, no knockback, play shield sound, damage shield durability.
 *
 * Policy:
 * - If recruited villager is USING a BLOCK item (shield) and attacker is within a 160-degree cone in front:
 *   - cancel incoming damage
 *   - play shield block sound
 *   - damage the shield (vanilla-ish)
 *   - cancel knockback for that tick (belt-and-suspenders)
 */
public final class VillagerManualShieldBlocker {

    private static volatile boolean registered = false;

    /** 160-degree cone in front => half-angle 80 deg => dot >= cos(80°). */
    private static final double COS_HALF_ANGLE = Math.cos(Math.toRadians(80.0));

    /** NBT key stored on the victim for 1 tick to cancel knockback. */
    private static final String PD_BLOCKED_TICK = "ezvr_blocked_tick";

    /** Throttle block logs per victim. */
    private static final Map<UUID, Long> LAST_LOG_TICK = new HashMap<>();
    private static final long LOG_INTERVAL_TICKS = 10L;

    // Reflection cache for ItemStack#hurtAndBreak signature drift
    private static volatile Method HURT_AND_BREAK_SLOT = null;   // hurtAndBreak(int, LivingEntity, EquipmentSlot)
    private static volatile Method HURT_AND_BREAK_HAND = null;   // hurtAndBreak(int, LivingEntity, InteractionHand) (rare)
    private static volatile boolean HURT_METHOD_SCANNED = false;

    private VillagerManualShieldBlocker() {}

    public static void register() {
        if (registered) return;
        registered = true;

        NeoForge.EVENT_BUS.addListener(VillagerManualShieldBlocker::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(VillagerManualShieldBlocker::onKnockback);

        VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerManualShieldBlocker registered on NeoForge EVENT_BUS.");
    }

    // -------------------------------------------------------------------------
    // Damage interception
    // -------------------------------------------------------------------------

    private static void onIncomingDamage(LivingIncomingDamageEvent e) {
        try {
            if (e == null) return;

            LivingEntity victim0 = e.getEntity();
            if (!(victim0 instanceof Villager vill)) return;

            if (vill.level() == null || vill.level().isClientSide()) return;

            // Only for controllable/recruited villagers (avoid touching vanilla villagers globally)
            if (!isControllable(vill)) return;

            // Must be actively blocking (using a BLOCK item)
            BlockingState bs = getBlockingState(vill);
            if (!bs.blocking) return;

            DamageSource src = e.getSource();
            Entity attackerEnt = (src == null) ? null : src.getEntity();
            if (!(attackerEnt instanceof LivingEntity attacker)) return;

            // Must be in front cone
            if (!isInFrontCone(vill, attacker)) return;

            float amount = e.getAmount();
            if (amount <= 0.0f) {
                // Still treat as a block event for sound/knockback suppression if desired.
                // But if amount is 0, durability damage would be weird. Skip durability.
                markBlockedThisTick(vill);
                playShieldBlockSound(vill);
                cancelDamage(e);
                return;
            }

            // Cancel incoming damage (prevents hurt sound, red flash, etc. in most cases)
            cancelDamage(e);

            // Mark for knockback cancel this tick
            markBlockedThisTick(vill);

            // Shield durability (vanilla-ish): 1 + floor(damage), min 1
            int shieldDamage = 1 + (int) Math.floor(amount);
            if (shieldDamage < 1) shieldDamage = 1;

            damageShield(vill, bs.hand, bs.item, shieldDamage);

            // Play shield block SFX
            playShieldBlockSound(vill);

            // Optional: log occasionally
            logBlock(vill, attacker, src, amount, shieldDamage, bs);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerManualShieldBlocker.onIncomingDamage failed (soft): {}", t.toString());
        }
    }

    private static void cancelDamage(LivingIncomingDamageEvent e) {
        try {
            // Prefer cancel; also force amount to 0 if setter exists.
            try {
                e.setCanceled(true);
            } catch (Throwable ignored) {}

            try {
                // Most versions expose setAmount(float)
                Method m = e.getClass().getMethod("setAmount", float.class);
                m.invoke(e, 0.0f);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Knockback cancellation (belt-and-suspenders)
    // -------------------------------------------------------------------------

    private static void onKnockback(LivingKnockBackEvent e) {
        try {
            if (e == null) return;

            LivingEntity victim0 = e.getEntity();
            if (!(victim0 instanceof Villager vill)) return;

            if (vill.level() == null || vill.level().isClientSide()) return;

            if (!isControllable(vill)) return;

            long now = vill.level().getGameTime();
            long tick = vill.getPersistentData().getLong(PD_BLOCKED_TICK);
            if (tick != now) return;

            // Cancel knockback outright if possible; otherwise set strength to 0.
            boolean canceled = false;
            try {
                e.setCanceled(true);
                canceled = true;
            } catch (Throwable ignored) {}

            if (!canceled) {
                try {
                    e.setStrength(0.0f);
                } catch (Throwable ignored) {}
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerManualShieldBlocker.onKnockback failed (soft): {}", t.toString());
        }
    }

    private static void markBlockedThisTick(Villager vill) {
        try {
            if (vill == null || vill.level() == null) return;
            vill.getPersistentData().putLong(PD_BLOCKED_TICK, vill.level().getGameTime());
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Blocking detection + cone math
    // -------------------------------------------------------------------------

    private static final class BlockingState {
        final boolean blocking;
        final InteractionHand hand;
        final ItemStack item;

        BlockingState(boolean blocking, InteractionHand hand, ItemStack item) {
            this.blocking = blocking;
            this.hand = hand;
            this.item = item == null ? ItemStack.EMPTY : item;
        }
    }

    private static BlockingState getBlockingState(Villager vill) {
        try {
            if (vill == null) return new BlockingState(false, null, ItemStack.EMPTY);

            if (!vill.isUsingItem()) return new BlockingState(false, null, ItemStack.EMPTY);

            InteractionHand hand;
            ItemStack using;
            try {
                hand = vill.getUsedItemHand();
            } catch (Throwable ignored) {
                hand = null;
            }

            try {
                using = vill.getUseItem();
            } catch (Throwable ignored) {
                using = ItemStack.EMPTY;
            }

            if (using == null || using.isEmpty()) return new BlockingState(false, hand, ItemStack.EMPTY);

            // Consider anything with UseAnim.BLOCK as "shield-like".
            UseAnim anim;
            try {
                anim = using.getUseAnimation();
            } catch (Throwable ignored) {
                anim = UseAnim.NONE;
            }

            boolean ok = (anim == UseAnim.BLOCK);
            return new BlockingState(ok, hand, using);

        } catch (Throwable ignored) {
            return new BlockingState(false, null, ItemStack.EMPTY);
        }
    }

    private static boolean isInFrontCone(Villager vill, LivingEntity attacker) {
        try {
            if (vill == null || attacker == null) return false;

            Vec3 villPos = vill.position();
            Vec3 attPos = attacker.position();

            Vec3 toAtt = attPos.subtract(villPos);
            double len2 = toAtt.lengthSqr();
            if (len2 < 1.0e-6) return true;

            Vec3 toAttN = toAtt.normalize();

            // Use look angle (forward vector). This includes pitch; for blocking we want horizontal.
            Vec3 fwd = vill.getLookAngle();
            if (fwd == null) return false;

            Vec3 fwd2 = new Vec3(fwd.x, 0.0, fwd.z);
            Vec3 to2 = new Vec3(toAttN.x, 0.0, toAttN.z);

            double f2 = fwd2.lengthSqr();
            double t2 = to2.lengthSqr();
            if (f2 < 1.0e-6 || t2 < 1.0e-6) return false;

            fwd2 = fwd2.normalize();
            to2 = to2.normalize();

            double dot = fwd2.dot(to2);

            return dot >= COS_HALF_ANGLE;

        } catch (Throwable ignored) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Shield durability + sound
    // -------------------------------------------------------------------------

    private static void damageShield(Villager vill, InteractionHand hand, ItemStack using, int amount) {
        try {
            if (vill == null) return;
            if (using == null || using.isEmpty()) return;
            if (amount <= 0) return;

            // Only damage if item is damageable
            try {
                if (!using.isDamageableItem()) return;
            } catch (Throwable ignored) {
                // If API changed, try anyway.
            }

            // Determine equipment slot from hand
            EquipmentSlot slot = null;
            try {
                if (hand != null) {
                    slot = LivingEntity.getSlotForHand(hand);
                }
            } catch (Throwable ignored) {}

            if (slot == null) {
                // Fallback: if it's the offhand item and matches
                ItemStack off = safeOff(vill);
                if (!off.isEmpty() && ItemStack.isSameItemSameComponents(off, using)) {
                    slot = EquipmentSlot.OFFHAND;
                } else {
                    slot = EquipmentSlot.MAINHAND;
                }
            }

            // Apply durability with best-effort signature handling
            if (!HURT_METHOD_SCANNED) warmupHurtAndBreakMethods();

            boolean applied = false;

            if (HURT_AND_BREAK_SLOT != null) {
                try {
                    HURT_AND_BREAK_SLOT.invoke(using, amount, vill, slot);
                    applied = true;
                } catch (Throwable ignored) {}
            }

            if (!applied && HURT_AND_BREAK_HAND != null && hand != null) {
                try {
                    HURT_AND_BREAK_HAND.invoke(using, amount, vill, hand);
                    applied = true;
                } catch (Throwable ignored) {}
            }

            if (!applied) {
                // Last resort: try to find any hurtAndBreak(int, LivingEntity, ..) each call (slow but safe).
                try {
                    for (Method m : ItemStack.class.getMethods()) {
                        if (!"hurtAndBreak".equals(m.getName())) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length != 3) continue;
                        if (p[0] != int.class) continue;
                        if (!LivingEntity.class.isAssignableFrom(p[1])) continue;

                        // third param can vary; attempt invoke with slot first, then hand
                        try {
                            if (slot != null && p[2].isAssignableFrom(EquipmentSlot.class)) {
                                m.invoke(using, amount, vill, slot);
                                applied = true;
                                break;
                            }
                        } catch (Throwable ignored) {}

                        try {
                            if (hand != null && p[2].isAssignableFrom(InteractionHand.class)) {
                                m.invoke(using, amount, vill, hand);
                                applied = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }

            if (!applied) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [block] Failed to apply shield durability (villager={})", vill.getUUID());
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [block] damageShield failed (soft): {}", t.toString());
        }
    }

    private static void warmupHurtAndBreakMethods() {
        HURT_METHOD_SCANNED = true;
        try {
            // Prefer hurtAndBreak(int, LivingEntity, EquipmentSlot)
            try {
                HURT_AND_BREAK_SLOT = ItemStack.class.getMethod("hurtAndBreak", int.class, LivingEntity.class, EquipmentSlot.class);
            } catch (Throwable ignored) {}

            // Some variants use InteractionHand
            try {
                HURT_AND_BREAK_HAND = ItemStack.class.getMethod("hurtAndBreak", int.class, LivingEntity.class, InteractionHand.class);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    private static void playShieldBlockSound(Villager vill) {
        try {
            if (vill == null || vill.level() == null) return;

            BlockPos pos = vill.blockPosition();
            vill.level().playSound(
                    null,
                    pos,
                    SoundEvents.SHIELD_BLOCK,
                    SoundSource.PLAYERS,
                    1.0f,
                    0.9f + (vill.getRandom().nextFloat() * 0.2f)
            );
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Logging
    // -------------------------------------------------------------------------

    private static void logBlock(Villager vill, LivingEntity attacker, DamageSource src, float dmg, int shieldDmg, BlockingState bs) {
        try {
            if (vill == null) return;
            long now = vill.level() == null ? 0L : vill.level().getGameTime();
            UUID id = vill.getUUID();
            if (id == null) return;

            Long last = LAST_LOG_TICK.get(id);
            if (last != null && (now - last) < LOG_INTERVAL_TICKS) return;
            LAST_LOG_TICK.put(id, now);

            String attackerId = safeEntityId(attacker);
            String srcId = safeSourceId(src);

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] [block] BLOCKED victim={} attacker={} src={} dmg={} shieldDmg={} usingHand={} usingItem={}",
                    vill.getUUID(),
                    attackerId,
                    srcId,
                    fmt2(dmg),
                    shieldDmg,
                    String.valueOf(bs.hand),
                    safeItemId(bs.item)
            );
        } catch (Throwable ignored) {}
    }

    private static String safeEntityId(Entity e) {
        try {
            if (e == null) return "null";
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return (id == null ? e.getType().toString() : id.toString()) + "@" + e.getUUID();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String safeSourceId(DamageSource src) {
        try {
            if (src == null) return "null";
            return String.valueOf(src);
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String safeItemId(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "empty";
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
            return id == null ? String.valueOf(st.getItem()) : id.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String fmt2(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return "0";
        return String.valueOf(Mth.floor(v * 100.0f) / 100.0f);
    }

    private static ItemStack safeOff(Villager v) {
        try { return v == null ? ItemStack.EMPTY : v.getOffhandItem(); } catch (Throwable ignored) { return ItemStack.EMPTY; }
    }

    private static boolean isControllable(Villager vill) {
        try {
            // Your core gate: recruited villagers only
            return RecruitService.isRecruited(vill);
        } catch (Throwable t) {
            return false;
        }
    }
}
