// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerCombatAttributeService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

/**
 * Applies server-side attribute modifiers derived from villager combat stat "points".
 *
 * Notes for this mappings/version:
 * - Attributes.* are Holder [Attribute]
 * - AttributeModifier is keyed by ResourceLocation (NOT UUID)
 * - AttributeInstance.removeModifier takes ResourceLocation or AttributeModifier
 */
public final class VillagerCombatAttributeService {

    private VillagerCombatAttributeService() {}

    // Stable modifier IDs so modifiers do NOT stack and can be safely replaced.
    private static final ResourceLocation MOD_VITALITY = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "combat_vitality");
    private static final ResourceLocation MOD_AGILITY  = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "combat_agility");
    private static final ResourceLocation MOD_STRENGTH = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "combat_strength");
    private static final ResourceLocation MOD_ARMOR    = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "combat_armor");

    /**
     * Runtime baselines (server-side) that are NOT part of the stat system.
     *
     * Reason:
     * Villager-like entities may have ATTACK_DAMAGE/ARMOR attributes added at runtime and end up
     * with a "mystery default" base (often 0). If base is 0 and Strength delta is negative,
     * vanilla melee can become permanently 0 damage unless the entity holds a weapon.
     *
     * These baselines ensure "attribute exists and has a sane base", but we intentionally do NOT clamp
     * the final (base + Strength delta + equipment) result. That is controlled by your stats + gear.
     */
    private static final double BASELINE_ATTACK_DAMAGE = 1.0D;
    private static final double BASELINE_ARMOR = 0.0D;

    /**
     * Apply combat modifiers if this is a supported villager/merchant entity.
     * Safe to call many times; it replaces existing modifiers by ResourceLocation.
     */
    public static void applyCombatModifiers(Entity e) {
        try {
            if (e == null) return;

            // Same scope as stats: villagers/wandering traders/modded merchants.
            if (!VillagerStatsService.isSupportedMerchantEntity(e)) return;

            // Must be a LivingEntity to have attributes
            if (!(e instanceof LivingEntity le)) return;

            // Ensure points exist, then read them
            VillagerStatsService.ensureStats(e);

            CompoundTag pd;
            try {
                pd = e.getPersistentData();
            } catch (Throwable t) {
                return;
            }
            if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) return;

            CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);

            int vitPts = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_VITALITY));
            int agiPts = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_AGILITY));
            int strPts = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_STRENGTH));
            int armPts = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_ARMOR));

            // Ensure newly-added attributes have sane base values at runtime.
            // This is not a "stat safeguard"; it's a "entity has working attribute instances" safeguard.
            ensureBaseAtLeast(le, Attributes.ATTACK_DAMAGE, BASELINE_ATTACK_DAMAGE);
            ensureBaseAtLeast(le, Attributes.ARMOR, BASELINE_ARMOR);

            // Points -> deltas (ADD_VALUE), using server config bounds.
            double vitDelta = lerpFromPoints(vitPts, ServerConfig.vitalityMinHealth, ServerConfig.vitalityMaxHealth);
            double agiDelta = lerpFromPoints(agiPts, ServerConfig.agilityMinSpeed, ServerConfig.agilityMaxSpeed);
            double strDelta = lerpFromPoints(strPts, ServerConfig.strengthMinDamage, ServerConfig.strengthMaxDamage);
            double armDelta = lerpFromPoints(armPts, ServerConfig.armorMin, ServerConfig.armorMax);

            // Keep only "game safety" clamps:
            // - Max health should not drop below 1 HP worth of max health.
            // - Movement speed should not become <= 0 (can break navigation / physics in practice).
            boolean okVit = applyAddValue(le, Attributes.MAX_HEALTH, MOD_VITALITY, vitDelta, 1.0);
            boolean okAgi = applyAddValue(le, Attributes.MOVEMENT_SPEED, MOD_AGILITY, agiDelta, 0.01);

            // IMPORTANT: No clamp for Strength. If stats drive attack_damage below 0, vanilla will effectively clamp it.
            // Equipping weapons can bring it back above 0 again.
            boolean okStr = applyAddValue(le, Attributes.ATTACK_DAMAGE, MOD_STRENGTH, strDelta, null);

            // IMPORTANT: No clamp for Armor. If stats drive armor below 0, vanilla behavior applies.
            boolean okArm = applyAddValue(le, Attributes.ARMOR, MOD_ARMOR, armDelta, null);

            // If max health changed:
            // - If max increased, heal to full (so 20->30 becomes 30/30, not 20/30)
            // - If max decreased, clamp down to max
            // - Always keep at least 1 HP
            if (okVit) {
                try {
                    float newMax = le.getMaxHealth();
                    float cur = le.getHealth();

                    if (vitDelta > 0.000001) {
                        le.setHealth(newMax);
                    } else {
                        if (cur > newMax) le.setHealth(newMax);
                        if (le.getHealth() < 1.0f) le.setHealth(1.0f);
                    }
                } catch (Throwable ignored) {}
            }

            // Post-apply debug: show actual base/value for the important ones.
            double atkBase = readBase(le, Attributes.ATTACK_DAMAGE);
            double atkVal  = readValue(le, Attributes.ATTACK_DAMAGE);
            double armBase = readBase(le, Attributes.ARMOR);
            double armVal  = readValue(le, Attributes.ARMOR);

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Applied combat modifiers entityId={} uuid={} vitDelta={} agiDelta={} strDelta={} armDelta={} ok=[{},{},{},{}] atkBase={} atkVal={} armorBase={} armorVal={}",
                    e.getId(), e.getUUID(),
                    trim3(vitDelta), trim3(agiDelta), trim3(strDelta), trim3(armDelta),
                    okVit, okAgi, okStr, okArm,
                    trim3(atkBase), trim3(atkVal),
                    trim3(armBase), trim3(armVal)
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatAttributeService.applyCombatModifiers failed (soft): {}", t.toString());
        }
    }

    /**
     * Ensure an attribute exists and that its base value is at least minBase.
     * This is a runtime safety net in case the attribute was added without a base default
     * or another mod altered the supplier defaults.
     */
    private static void ensureBaseAtLeast(LivingEntity le, Holder<Attribute> attr, double minBase) {
        try {
            if (le == null || attr == null) return;
            AttributeInstance inst = le.getAttribute(attr);
            if (inst == null) return;

            double base = inst.getBaseValue();
            if (Double.isNaN(base) || Double.isInfinite(base)) base = 0.0;

            if (base < minBase) {
                inst.setBaseValue(minBase);
                if (VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug(
                            "[VillagerOverhaul] ensureBaseAtLeast applied (uuid={} attr={} {}->{} valueNow={})",
                            le.getUUID(),
                            safeAttrName(attr),
                            trim3(base),
                            trim3(minBase),
                            trim3(inst.getValue())
                    );
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String safeAttrName(Holder<Attribute> attr) {
        try {
            return String.valueOf(attr);
        } catch (Throwable t) {
            return "attr";
        }
    }

    private static double readBase(LivingEntity le, Holder<Attribute> attr) {
        try {
            AttributeInstance inst = le.getAttribute(attr);
            if (inst == null) return 0.0;
            return inst.getBaseValue();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static double readValue(LivingEntity le, Holder<Attribute> attr) {
        try {
            AttributeInstance inst = le.getAttribute(attr);
            if (inst == null) return 0.0;
            return inst.getValue();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /**
     * Apply an ADD_VALUE attribute modifier, replacing the existing one with the same id if present.
     *
     * @param minFinal If non-null, clamps so (base + amount) is at least minFinal (basic safety).
     *                 Use ONLY for values that can truly break gameplay if <= 0 (e.g. speed, max health).
     */
    private static boolean applyAddValue(
            LivingEntity le,
            Holder<Attribute> attr,
            ResourceLocation modifierId,
            double amount,
            Double minFinal
    ) {
        try {
            if (le == null || attr == null || modifierId == null) return false;

            AttributeInstance inst = le.getAttribute(attr);
            if (inst == null) {
                return false;
            }

            // Only apply this clamp where "bad values" truly break the game.
            if (minFinal != null) {
                double base = inst.getBaseValue();
                if ((base + amount) < minFinal) {
                    amount = minFinal - base;
                }
            }

            // Remove previous modifier (by id) so it never stacks.
            try {
                inst.removeModifier(modifierId);
            } catch (Throwable ignored) {}

            AttributeModifier mod = new AttributeModifier(modifierId, amount, AttributeModifier.Operation.ADD_VALUE);

            // Prefer transient so it doesn't permanently bake into entity NBT.
            try {
                inst.addTransientModifier(mod);
            } catch (Throwable t) {
                try {
                    inst.addPermanentModifier(mod);
                } catch (Throwable ignored) {
                    return false;
                }
            }

            return true;

        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Map points [-100..100] -> [min..max] linearly.
     */
    private static double lerpFromPoints(int points, double min, double max) {
        int p = VillagerStatsService.clampPoints(points);
        double t = (p + 100.0) / 200.0; // 0..1
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        return min + (max - min) * t;
    }

    private static double trim3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 1000.0) / 1000.0;
    }
}
