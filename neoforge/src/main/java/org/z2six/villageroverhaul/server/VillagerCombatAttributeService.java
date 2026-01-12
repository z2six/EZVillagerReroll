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
 * - Attributes.* are Holder<Attribute>
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

            // Points -> deltas (ADD_VALUE), using server config bounds.
            double vitDelta = lerpFromPoints(vitPts, ServerConfig.vitalityMinHealth, ServerConfig.vitalityMaxHealth);
            double agiDelta = lerpFromPoints(agiPts, ServerConfig.agilityMinSpeed, ServerConfig.agilityMaxSpeed);
            double strDelta = lerpFromPoints(strPts, ServerConfig.strengthMinDamage, ServerConfig.strengthMaxDamage);
            double armDelta = lerpFromPoints(armPts, ServerConfig.armorMin, ServerConfig.armorMax);

            boolean okVit = applyAddValue(le, Attributes.MAX_HEALTH, MOD_VITALITY, vitDelta, 1.0);
            boolean okAgi = applyAddValue(le, Attributes.MOVEMENT_SPEED, MOD_AGILITY, agiDelta, 0.01);
            boolean okStr = applyAddValue(le, Attributes.ATTACK_DAMAGE, MOD_STRENGTH, strDelta, null);
            boolean okArm = applyAddValue(le, Attributes.ARMOR, MOD_ARMOR, armDelta, null);

            // If max health changed:
            // - If max increased, heal to full (so 20->30 becomes 30/30, not 20/30)
            // - If max decreased, clamp down to max
            // - Always keep at least 1 HP
            if (okVit) {
                try {
                    float oldMax = le.getMaxHealth();

                    // Re-read after modifier application: the attribute value has already been updated,
                    // but getMaxHealth() should now reflect the new max. We still want an "old vs new" comparison.
                    //
                    // Because we don't have "old max" cached before modifier application in this scope,
                    // we detect the "increase" case by checking whether current health is below max AND
                    // the modifier amount was positive (vitality increased max health).
                    float newMax = le.getMaxHealth();
                    float cur = le.getHealth();

                    // If our vitality delta is positive, treat it as a max-health increase and heal to full.
                    // (If you allow negative vitality, this will not full-heal on decreases.)
                    if (vitDelta > 0.000001) {
                        le.setHealth(newMax);
                    } else {
                        // No increase => just clamp if needed
                        if (cur > newMax) le.setHealth(newMax);
                        if (le.getHealth() < 1.0f) le.setHealth(1.0f);
                    }
                } catch (Throwable ignored) {}
            }

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] Applied combat modifiers entityId={} uuid={} vitDelta={} agiDelta={} strDelta={} armDelta={} ok=[{},{},{},{}]",
                    e.getId(), e.getUUID(),
                    trim3(vitDelta), trim3(agiDelta), trim3(strDelta), trim3(armDelta),
                    okVit, okAgi, okStr, okArm
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatAttributeService.applyCombatModifiers failed (soft): {}", t.toString());
        }
    }

    /**
     * Apply an ADD_VALUE attribute modifier, replacing the existing one with the same id if present.
     *
     * @param minFinal If non-null, clamps so (base + amount) is at least minFinal (basic safety).
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
                // Some entity types may not register certain attributes (esp. attack damage/armor).
                return false;
            }

            // Basic safety clamp to avoid invalid values (especially for speed/health).
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
                // Fallback if transient isn't available in your mappings:
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
