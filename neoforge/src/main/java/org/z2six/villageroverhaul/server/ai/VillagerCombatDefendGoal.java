// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDefendGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.server.CombatSettingsService;
import org.z2six.villageroverhaul.server.IgnoredTargetService;
import org.z2six.villageroverhaul.server.RecruitService;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

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
            if (VillagerBrain.isStorageActive(vill)) return false;

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

            if (findRecentDefendTarget() != null) return true;

            logNoThreat("no_recent_attacker");
            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDefendGoal.canUse failed (soft): {}", t.toString());
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;
            if (VillagerBrain.isStorageActive(vill)) return false;

            if (!VillagerBrain.shouldCombatActNow(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;
            if (VillagerBrain.getCombatMode(vill) != VillagerBrain.CombatMode.DEFEND) return false;

            if (targetUuid == null) return false;
            LivingEntity t = findThreatByUuid(targetUuid);
            return t != null && t.isAlive() && !IgnoredTargetService.isIgnoredByVillagers(t);
        } catch (Throwable t) {
            return false;
        }
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

            // Never allow targeting owner or allied recruited villagers (same owner).
            if (target != null && isFriendlyToVillager(vill, target)) {
                target = null;
                targetUuid = null;
            }
            if (target != null && IgnoredTargetService.isIgnoredByVillagers(target)) {
                target = null;
                targetUuid = null;
            }

            // Priority 1: if THIS villager was attacked recently, retaliate against that attacker (unless friendly).
            LivingEntity selfAttacker = null;
            try { selfAttacker = vill.getLastHurtByMob(); } catch (Throwable ignored) {}
            if (selfAttacker != null && selfAttacker.isAlive()) {
                if (IgnoredTargetService.isIgnoredByVillagers(selfAttacker)) {
                    selfAttacker = null;
                }
            }
            if (selfAttacker != null && selfAttacker.isAlive()) {
                int ts = 0;
                try { ts = vill.getLastHurtByMobTimestamp(); } catch (Throwable ignored) { ts = 0; }
                if ((vill.tickCount - ts) <= 40) {
                    CombatSettingsService.TriggerCheck chk = CombatSettingsService.checkTrigger(vill, VillagerBrain.CombatMode.DEFEND, selfAttacker, vill);
                    if (chk.ok && !isFriendlyToVillager(vill, selfAttacker)) {
                        targetUuid = selfAttacker.getUUID();
                        target = selfAttacker;
                    }
                }
            }

            // Otherwise, choose a defend target from recent nearby hurt events.
            if (target == null) {
                LivingEntity best = findRecentDefendTarget();
                if (best != null) {
                    targetUuid = best.getUUID();
                    target = best;
                }
            }

            if (target == null) {
                if (VillagerBrain.isCombatEngaged(vill)) {
                    VillagerCombatDirector.finishCombatAndResume(vill, "defend_no_target");
                }
                return;
            }

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

    private LivingEntity findRecentDefendTarget() {
        try {
            if (vill == null || vill.level() == null) return null;
            long now = vill.level().getGameTime();
            if ((now - lastScanAt) < 5L) return null;
            lastScanAt = now;

            AABB box = vill.getBoundingBox().inflate(26.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());

            LivingEntity best = null;
            double bestDist = Double.MAX_VALUE;

            for (LivingEntity victim : nearby) {
                LivingEntity attacker = victim.getLastHurtByMob();
                if (attacker == null) continue;

                int hurtAt = victim.getLastHurtByMobTimestamp();
                if ((victim.tickCount - hurtAt) > 40) continue;

                if (attacker == vill) continue;

                CombatSettingsService.TriggerCheck check =
                        CombatSettingsService.checkTrigger(vill, VillagerBrain.CombatMode.DEFEND, attacker, victim);
                if (!check.ok) {
                    if ((now - lastRejectLogAt) > 40L) {
                        lastRejectLogAt = now;
                        VillagerOverhaul.LOG().debug(
                                "[VillagerOverhaul] DEFEND candidate rejected (villager={} attacker={} target={} reason={})",
                                vill.getUUID(),
                                attacker.getUUID(),
                                victim.getUUID(),
                                check.reason
                        );
                    }
                    continue;
                }

                LivingEntity toAttack = pickTargetToAttack(attacker, victim, check.trigger);
                if (toAttack == null) continue;

                if (IgnoredTargetService.isIgnoredByVillagers(toAttack)) continue;
                if (isFriendlyToVillager(vill, toAttack)) continue;

                double d2 = vill.distanceToSqr(toAttack);
                if (d2 < bestDist) {
                    bestDist = d2;
                    best = toAttack;
                }
            }

            if (best != null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] DEFEND threat set (villager={} target={})",
                        vill.getUUID(), best.getUUID());
            }
            return best;
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * For defend triggers:
     * - owner_attacks => attack the entity the owner attacked (victim)
     * - owner_attacked => attack the attacker
     * - entity_* => attack the attacker
     */
    private LivingEntity pickTargetToAttack(LivingEntity attacker, LivingEntity victim, String trigger) {
        try {
            if (attacker == null || victim == null) return null;
            String t = trigger == null ? "" : trigger;
            if (t.equals("owner_attacks")) return victim;
            if (t.equals("owner_attacked")) return attacker;
            return attacker;
        } catch (Throwable ignored) {
            return attacker;
        }
    }

    private static boolean isFriendlyToVillager(Villager vill, LivingEntity candidate) {
        try {
            if (vill == null || candidate == null) return true;
            if (candidate == vill) return true;

            UUID owner = RecruitService.getRecruiterUuid(vill);
            if (owner == null) return false;

            if (owner.equals(candidate.getUUID())) return true;

            if (candidate instanceof Villager other) {
                if (!RecruitService.isRecruited(other)) return false;
                UUID otherOwner = RecruitService.getRecruiterUuid(other);
                return otherOwner != null && owner.equals(otherOwner);
            }

            return false;
        } catch (Throwable ignored) {
            return false;
        }
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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] DEFEND waiting (villager={} reason={})", vill.getUUID(), reason);
        } catch (Throwable ignored) {}
    }
}
