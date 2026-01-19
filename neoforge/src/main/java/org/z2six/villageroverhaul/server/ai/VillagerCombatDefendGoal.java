// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDefendGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.server.CombatSettingsService;

import java.util.EnumSet;
import java.util.List;

/**
 * Combat module: DEFEND (activation only in Step 1).
 *
 * IMPORTANT (Step 1 behavior):
 * - Does NOT claim MOVE/JUMP/LOOK flags (so it will not interfere with existing movement AI yet).
 * - Only indicates "this mode is active" and provides a safe hook point for Step 3 AI.
 */
public final class VillagerCombatDefendGoal extends Goal {

    private final Villager vill;
    private boolean loggedActive = false;
    private java.util.UUID targetUuid = null;
    private long lastNoThreatLogAt = 0L;
    private long lastRejectLogAt = 0L;
    private long lastScanAt = 0L;

    public VillagerCombatDefendGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;

            if (!VillagerBrain.shouldCombatActNow(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;

            if (VillagerBrain.getCombatMode(vill) != VillagerBrain.CombatMode.DEFEND) return false;

            CombatSettings settings = CombatSettingsService.getPerVillager(vill);
            if (settings == null) {
                logNoThreat("no_settings");
                return false;
            }
            CombatSettings.ModeSettings m = settings.defend;
            if (!m.ownerAttacked.enabled && !m.ownerAttacks.enabled && !m.entityAttacks.enabled && !m.entityAttacked.enabled) {
                logNoThreat("no_triggers_enabled");
                return false;
            }

            if (findRecentAttacker(vill) != null) return true;

            logNoThreat("no_recent_attacker");
            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDefendGoal.canUse failed (soft): {}", t.toString());
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        try {
            loggedActive = false;
            targetUuid = null;
            lastNoThreatLogAt = 0L;
            lastRejectLogAt = 0L;
            lastScanAt = 0L;
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            // Step 1: do nothing besides optional debug.
            if (!loggedActive) {
                loggedActive = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat goal active: DEFEND (villager={}, mode={})",
                        vill == null ? "null" : vill.getUUID(),
                        vill == null ? "null" : VillagerBrain.getMode(vill).id);
            }

            if (vill == null || vill.level() == null) return;

            LivingEntity target = null;
            if (targetUuid != null) {
                target = findThreatByUuid(targetUuid);
                if (target == null || !target.isAlive()) {
                    targetUuid = null;
                }
            }

            if (target == null) {
                LivingEntity attacker = findRecentAttacker(vill);
                if (attacker != null) {
                    targetUuid = attacker.getUUID();
                    target = attacker;
                }
            }

            if (target == null) return;

            VillagerCombatDirector.tickAttack(vill, target);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDefendGoal.tick failed (soft): {}", t.toString());
        }
    }

    @Override
    public void stop() {
        try {
            loggedActive = false;
            targetUuid = null;
            lastNoThreatLogAt = 0L;
            lastRejectLogAt = 0L;
            lastScanAt = 0L;
            VillagerCombatDirector.stop(vill);
        } catch (Throwable ignored) {}
    }

    private LivingEntity findRecentAttacker(Villager vill) {
        try {
            if (vill == null || vill.level() == null) return null;
            long now = vill.level().getGameTime();
            if ((now - lastScanAt) < 5L) return null;
            lastScanAt = now;

            AABB box = vill.getBoundingBox().inflate(26.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());

            for (LivingEntity target : nearby) {
                LivingEntity attacker = target.getLastHurtByMob();
                if (attacker == null) continue;

                int hurtAt = target.getLastHurtByMobTimestamp();
                if ((target.tickCount - hurtAt) > 40) continue;

                if (attacker == vill) continue;

                CombatSettingsService.TriggerCheck check =
                        CombatSettingsService.checkTrigger(vill, VillagerBrain.CombatMode.DEFEND, attacker, target);
                if (!check.ok) {
                    if ((now - lastRejectLogAt) > 40L) {
                        lastRejectLogAt = now;
                        VillagerOverhaul.LOG().info(
                                "[VillagerOverhaul] DEFEND candidate rejected (villager={} attacker={} target={} reason={})",
                                vill.getUUID(),
                                attacker.getUUID(),
                                target.getUUID(),
                                check.reason
                        );
                    }
                    continue;
                }

                VillagerOverhaul.LOG().info("[VillagerOverhaul] DEFEND threat set (villager={} attacker={})",
                        vill.getUUID(), attacker.getUUID());
                return attacker;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private LivingEntity findThreatByUuid(java.util.UUID id) {
        try {
            if (id == null || vill == null || vill.level() == null) return null;
            AABB box = vill.getBoundingBox().inflate(32.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());
            for (LivingEntity e : nearby) {
                if (id.equals(e.getUUID())) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void logNoThreat(String reason) {
        try {
            if (vill == null || vill.level() == null) return;
            long now = vill.level().getGameTime();
            if ((now - lastNoThreatLogAt) < 40L) return;
            lastNoThreatLogAt = now;
            VillagerOverhaul.LOG().info("[VillagerOverhaul] DEFEND waiting (villager={} reason={})", vill.getUUID(), reason);
        } catch (Throwable ignored) {}
    }
}
