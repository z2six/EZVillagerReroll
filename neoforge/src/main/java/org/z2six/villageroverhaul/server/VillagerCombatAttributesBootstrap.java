// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerCombatAttributesBootstrap.java
package org.z2six.villageroverhaul.server;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;

/**
 * Ensures villager-like entities actually HAVE the vanilla attributes we want to modify.
 *
 * Villagers do NOT include ATTACK_DAMAGE by default, so applying Strength would fail (AttributeInstance null),
 * and vanilla melee damage (Mob#doHurtTarget) would compute as ~0 if no sensible base is present.
 *
 * This runs on the MOD event bus (not the gameplay bus).
 *
 * Key detail:
 * EntityAttributeModificationEvent exposes:
 *   - add(type, attribute)
 *   - add(type, attribute, baseValue)
 *   - has(type, attribute)
 *
 * We use has(...) so we do NOT override vanilla defaults or another mod's supplier values if already present.
 */
public final class VillagerCombatAttributesBootstrap {

    private static volatile boolean registered = false;

    private static volatile boolean REACH_SCANNED = false;
    private static volatile Holder<Attribute> ENTITY_REACH_ATTR = null;

    private VillagerCombatAttributesBootstrap() {}

    public static void register(IEventBus modBus) {
        if (modBus == null) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatAttributesBootstrap.register called with null modBus");
            return;
        }
        if (registered) return;
        registered = true;

        modBus.addListener(VillagerCombatAttributesBootstrap::onEntityAttributeModification);
        VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatAttributesBootstrap registered (mod bus).");
    }

    private static void onEntityAttributeModification(EntityAttributeModificationEvent e) {
        try {
            if (e == null) return;

            // Villager + Wandering Trader cover most “villager-like merchants”.
            ensureFor(e, EntityType.VILLAGER);
            ensureFor(e, EntityType.WANDERING_TRADER);

            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] EntityAttributeModificationEvent typesCount={}", e.getTypes().size());
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onEntityAttributeModification failed", t);
        }
    }

    private static void ensureFor(EntityAttributeModificationEvent e, EntityType<? extends LivingEntity> type) {
        try {
            if (e == null || type == null) return;

            // Common attributes that should exist anyway; only add if missing.
            ensurePresent(e, type, Attributes.MAX_HEALTH, null, "MAX_HEALTH");
            ensurePresent(e, type, Attributes.MOVEMENT_SPEED, null, "MOVEMENT_SPEED");

            // Core combat attributes we actively use.
            // Base attack damage: keep 1.0 baseline so bare-handed attacks aren't permanently 0 due to missing base.
            ensurePresent(e, type, Attributes.ATTACK_DAMAGE, 1.0D, "ATTACK_DAMAGE");
            ensurePresent(e, type, Attributes.ARMOR, 0.0D, "ARMOR");
            ensurePresent(e, type, Attributes.ARMOR_TOUGHNESS, 0.0D, "ARMOR_TOUGHNESS");

            // "May need later" combat / feel / tuning attributes (added at 0 unless you later decide otherwise).
            ensurePresent(e, type, Attributes.ATTACK_KNOCKBACK, 0.0D, "ATTACK_KNOCKBACK");
            ensurePresent(e, type, Attributes.KNOCKBACK_RESISTANCE, 0.0D, "KNOCKBACK_RESISTANCE");
            ensurePresent(e, type, Attributes.FOLLOW_RANGE, 0.0D, "FOLLOW_RANGE");
            ensurePresent(e, type, Attributes.STEP_HEIGHT, 0.0D, "STEP_HEIGHT");
            ensurePresent(e, type, Attributes.SCALE, 0.0D, "SCALE");

            // These exist in the Attributes list and can be useful later, but are often ignored by many entities.
            // Still safe to ensure-present at 0 without overriding if vanilla already supplies them. :contentReference[oaicite:3]{index=3}
            ensurePresent(e, type, Attributes.ATTACK_SPEED, 0.0D, "ATTACK_SPEED");
            ensurePresent(e, type, Attributes.LUCK, 0.0D, "LUCK");
            ensurePresent(e, type, Attributes.FLYING_SPEED, 0.0D, "FLYING_SPEED");
            ensurePresent(e, type, Attributes.JUMP_STRENGTH, 0.0D, "JUMP_STRENGTH");

            // Optional: NeoForge reach attribute (lets weapons contribute reach).
            Holder<Attribute> reach = tryGetNeoForgeEntityReachAttr();
            if (reach != null) {
                ensurePresent(e, type, reach, 0.0D, "ENTITY_REACH");
            }

        } catch (Throwable t) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] ensureFor failed (soft) type={} err={}", type, t.toString());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Holder<Attribute> tryGetNeoForgeEntityReachAttr() {
        try {
            if (!REACH_SCANNED) {
                REACH_SCANNED = true;
                try {
                    Class<?> cls = Class.forName("net.neoforged.neoforge.common.NeoForgeMod");
                    Field best = null;
                    for (Field f : cls.getFields()) {
                        String n = f.getName();
                        if (n == null) continue;
                        String ln = n.toLowerCase(java.util.Locale.ROOT);
                        if (!ln.contains("reach")) continue;
                        if (best == null) best = f;
                        if (ln.contains("entity") && ln.contains("reach")) {
                            best = f;
                            break;
                        }
                    }
                    if (best != null) {
                        Object v = best.get(null);
                        if (v instanceof Holder<?> h) ENTITY_REACH_ATTR = (Holder) h;
                    }
                } catch (Throwable ignored) {}
            }
            return ENTITY_REACH_ATTR;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Ensures the attribute exists on the entity type's AttributeSupplier builder.
     *
     * - If baseValue is null: uses event.add(type, attr) (does not set/override a base).
     * - If baseValue is non-null: uses event.add(type, attr, baseValue) but only if missing.
     *
     * We always check event.has(...) first to avoid overriding vanilla defaults. :contentReference[oaicite:4]{index=4}
     */
    private static void ensurePresent(
            EntityAttributeModificationEvent e,
            EntityType<? extends LivingEntity> type,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
            Double baseValue,
            String debugName
    ) {
        try {
            if (e == null || type == null || attr == null) return;

            if (e.has(type, attr)) {
                if (VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Attribute already present (type={} attr={})", type.toShortString(), debugName);
                }
                return;
            }

            if (baseValue == null) {
                e.add(type, attr);
                if (VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Added attribute (type={} attr={})", type.toShortString(), debugName);
                }
            } else {
                e.add(type, attr, baseValue.doubleValue());
                if (VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug(
                            "[VillagerOverhaul] Added attribute with base (type={} attr={} base={})",
                            type.toShortString(), debugName, baseValue
                    );
                }
            }
        } catch (Throwable t) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] ensurePresent failed (soft) type={} attr={} err={}",
                        type, debugName, t.toString()
                );
            }
        }
    }
}
