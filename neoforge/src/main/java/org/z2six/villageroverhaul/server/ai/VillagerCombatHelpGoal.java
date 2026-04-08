// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatHelpGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.HelpChatCommandService;
import org.z2six.villageroverhaul.server.IgnoredTargetService;
import org.z2six.villageroverhaul.server.RecruitService;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Combat module: HELP mode (chat-triggered "assist owner").
 *
 * Pulls a per-player target set from {@link HelpChatCommandService} and attacks them until the set is empty,
 * then restores the previous combat mode.
 */
public final class VillagerCombatHelpGoal extends Goal {

    private final Villager vill;
    private boolean loggedActive = false;
    private UUID currentTarget = null;
    private int lastObservedHurtByTs = 0;
    private int lastObservedHurtMobTs = 0;

    public VillagerCombatHelpGoal(Villager vill) {
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
            if (VillagerBrain.getCombatMode(vill) != VillagerBrain.CombatMode.HELP) return false;

            UUID owner = RecruitService.getRecruiterUuid(vill);
            if (owner == null) return false;

            // Keep the goal running even if there are no targets so we can gracefully exit HELP mode.
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        loggedActive = false;
        currentTarget = null;
        lastObservedHurtByTs = 0;
        lastObservedHurtMobTs = 0;
    }

    @Override
    public void tick() {
        try {
            if (!loggedActive) {
                loggedActive = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat goal active: HELP (villager={})", vill.getUUID());
            }

            UUID owner = RecruitService.getRecruiterUuid(vill);
            if (owner == null) {
                try { VillagerBrain.scheduleHelpReturnIfNeeded(vill); } catch (Throwable ignored) {}
                VillagerBrain.exitHelpToPreviousCombatMode(vill);
                VillagerCombatDirector.finishCombatAndResume(vill, "help_owner_missing");
                return;
            }

            // If the owner is actively fighting, keep adding targets in the background.
            tryObserveLocalCombat(owner);

            List<UUID> targets = HelpChatCommandService.getTargets(owner);
            LivingEntity best = findClosestTarget(targets);

            if (best == null) {
                // No targets: rally near the owner briefly so "help" still feels responsive.
                long now = 0L;
                try { now = vill.level().getGameTime(); } catch (Throwable ignored) { now = 0L; }
                if (HelpChatCommandService.isRallyActive(owner, now)) {
                    try {
                        if (vill.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                            var sp = sl.getServer().getPlayerList().getPlayer(owner);
                            if (sp != null && sp.isAlive()) {
                                double d2 = vill.distanceToSqr(sp);
                                // Move towards owner if not already close.
                                if (d2 > 9.0) {
                                    try { vill.getLookControl().setLookAt(sp, 30.0F, 30.0F); } catch (Throwable ignored2) {}
                                    try { vill.getNavigation().moveTo(sp, 0.6); } catch (Throwable ignored2) {}
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                try { VillagerBrain.scheduleHelpReturnIfNeeded(vill); } catch (Throwable ignored) {}
                VillagerBrain.exitHelpToPreviousCombatMode(vill);
                VillagerCombatDirector.finishCombatAndResume(vill, "help_no_targets");
                return;
            }

            currentTarget = best.getUUID();
            VillagerCombatDirector.tickAttack(vill, best);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatHelpGoal.tick failed (soft): {}", t.toString());
        }
    }

    @Override
    public void stop() {
        loggedActive = false;
        currentTarget = null;
        lastObservedHurtByTs = 0;
        lastObservedHurtMobTs = 0;
        try { VillagerCombatDirector.stop(vill); } catch (Throwable ignored) {}
    }

    private LivingEntity findClosestTarget(List<UUID> targets) {
        try {
            if (targets == null || targets.isEmpty()) return null;
            if (vill == null || vill.level() == null) return null;

            LivingEntity best = null;
            double bestD2 = Double.MAX_VALUE;

            for (UUID u : targets) {
                if (u == null) continue;
                LivingEntity e = null;
                try {
                    var ent = ((net.minecraft.server.level.ServerLevel) vill.level()).getEntity(u);
                    if (ent instanceof LivingEntity le && le.isAlive()) e = le;
                } catch (Throwable ignored) { e = null; }

                if (e == null || !e.isAlive()) continue;
                if (e == vill) continue;
                if (IgnoredTargetService.isIgnoredByVillagers(e)) continue;

                double d2 = vill.distanceToSqr(e);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = e;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void tryObserveLocalCombat(UUID owner) {
        try {
            // Observe this villager's own combat and add targets to the owner's session (helps when villagers get attacked).
            int hurtByTs = 0;
            int hurtMobTs = 0;
            try { hurtByTs = vill.getLastHurtByMobTimestamp(); } catch (Throwable ignored) { hurtByTs = 0; }
            try { hurtMobTs = vill.getLastHurtMobTimestamp(); } catch (Throwable ignored) { hurtMobTs = 0; }

            if (hurtByTs > lastObservedHurtByTs) {
                lastObservedHurtByTs = hurtByTs;
                LivingEntity attacker = null;
                try { attacker = vill.getLastHurtByMob(); } catch (Throwable ignored) { attacker = null; }
                if (attacker != null && attacker.isAlive()) HelpChatCommandService.addTarget(owner, attacker.getUUID());
            }

            if (hurtMobTs > lastObservedHurtMobTs) {
                lastObservedHurtMobTs = hurtMobTs;
                LivingEntity victim = null;
                try { victim = vill.getLastHurtMob(); } catch (Throwable ignored) { victim = null; }
                if (victim != null && victim.isAlive()) HelpChatCommandService.addTarget(owner, victim.getUUID());
            }
        } catch (Throwable ignored) {}
    }
}
