// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatFleeGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.CombatSettingsService;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Combat module: FLEE (activation only in Step 1).
 *
 * IMPORTANT (Step 1 behavior):
 * - Does NOT claim MOVE/JUMP/LOOK flags (so it will not interfere with existing movement AI yet).
 * - Only indicates "this mode is active" and provides a safe hook point for Step 3 AI.
 */
public final class VillagerCombatFleeGoal extends Goal {

    private final Villager vill;
    private boolean loggedActive = false;
    private UUID threatUuid = null;
    private Vec3 lastThreatPos = null;
    private long fleeUntilTick = 0L;
    private long lastThreatAt = 0L;
    private long nextRerouteAt = 0L;
    private long lastScanAt = 0L;
    private long lastNoThreatLogAt = 0L;
    private long lastRejectLogAt = 0L;

    // Tuning (hardcoded for now)
    private static final double FLEE_SPEED = 0.65;
    private static final int FLEE_DISTANCE = 16;
    private static final int FLEE_VERTICAL = 7;

    public VillagerCombatFleeGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;

            if (VillagerBrain.getCombatMode(vill) != VillagerBrain.CombatMode.FLEE) return false;
            if (!VillagerBrain.shouldCombatActNow(vill)) {
                logNoThreat("combat_inactive");
                return false;
            }

            if (VillagerBrain.isUiPaused(vill)) {
                logNoThreat("ui_paused");
                return false;
            }

            if (threatUuid != null && findThreatByUuid(threatUuid) != null) return true;

            LivingEntity attacker = findRecentAttacker(vill);
            if (attacker != null) return true;

            logNoThreat("no_recent_attacker");
            return false;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatFleeGoal.canUse failed (soft): {}", t.toString());
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
            threatUuid = null;
            lastThreatPos = null;
            fleeUntilTick = 0L;
            lastThreatAt = 0L;
            nextRerouteAt = 0L;
            lastScanAt = 0L;
            lastNoThreatLogAt = 0L;
            lastRejectLogAt = 0L;
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            if (vill == null || vill.level() == null || vill.level().isClientSide()) return;

            long now = vill.level().getGameTime();

            if (now - lastScanAt >= 5L) {
                lastScanAt = now;
                LivingEntity attacker = findRecentAttacker(vill);
                if (attacker != null) {
                    threatUuid = attacker.getUUID();
                    lastThreatPos = attacker.position();
                    lastThreatAt = now;
                    fleeUntilTick = now + 20L * 30L;
                    VillagerBrain.setCombatEngaged(vill, true);

                    VillagerOverhaul.LOG().info("[VillagerOverhaul] Flee threat set (villager={} attacker={})",
                            vill.getUUID(), attacker.getUUID());
                }
            }

            if (threatUuid == null) return;

            LivingEntity threat = findThreatByUuid(threatUuid);
            if (threat == null || !threat.isAlive()) {
                if (now - lastThreatAt > 40L) {
                    threatUuid = null;
                    VillagerBrain.setCombatEngaged(vill, false);
                    vill.getNavigation().stop();
                    return;
                }
            }

            if (fleeUntilTick > 0L && now > fleeUntilTick) {
                threatUuid = null;
                VillagerBrain.setCombatEngaged(vill, false);
                vill.getNavigation().stop();
                return;
            }

            if (now >= nextRerouteAt) {
                nextRerouteAt = now + 5L;
                tryReroute(threat);
            }

            if (!loggedActive) {
                loggedActive = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat goal active: FLEE (villager={}, mode={})",
                        vill == null ? "null" : vill.getUUID(),
                        vill == null ? "null" : VillagerBrain.getMode(vill).id);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatFleeGoal.tick failed (soft): {}", t.toString());
        }
    }

    @Override
    public void stop() {
        try {
            loggedActive = false;
            threatUuid = null;
            lastThreatPos = null;
            fleeUntilTick = 0L;
            lastThreatAt = 0L;
            nextRerouteAt = 0L;
            lastScanAt = 0L;
            lastNoThreatLogAt = 0L;
            lastRejectLogAt = 0L;
            VillagerBrain.setCombatEngaged(vill, false);
        } catch (Throwable ignored) {}
    }

    private LivingEntity findRecentAttacker(Villager vill) {
        try {
            if (vill == null || vill.level() == null) return null;

            AABB box = vill.getBoundingBox().inflate(26.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());

            for (LivingEntity target : nearby) {

                LivingEntity attacker = target.getLastHurtByMob();
                if (attacker == null) continue;

                int hurtAt = target.getLastHurtByMobTimestamp();
                if ((target.tickCount - hurtAt) > 40) continue;

                if (attacker == vill) continue;

                CombatSettingsService.TriggerCheck check =
                        CombatSettingsService.checkTrigger(vill, VillagerBrain.CombatMode.FLEE, attacker, target);
                if (!check.ok) {
                    long now = vill.level().getGameTime();
                    if ((now - lastRejectLogAt) > 40L) {
                        lastRejectLogAt = now;
                        VillagerOverhaul.LOG().info(
                                "[VillagerOverhaul] FLEE candidate rejected (villager={} attacker={} target={} reason={})",
                                vill.getUUID(),
                                attacker.getUUID(),
                                target.getUUID(),
                                check.reason
                        );
                    }
                    continue;
                }

                lastThreatPos = attacker.position();
                return attacker;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private LivingEntity findThreatByUuid(UUID id) {
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

    private void tryReroute(LivingEntity threat) {
        try {
            if (vill == null || vill.level() == null) return;

            Vec3 villPos = vill.position();
            Vec3 threatPos = (threat != null) ? threat.position() : lastThreatPos;

            Vec3 away = (threatPos != null) ? villPos.subtract(threatPos) : new Vec3(1.0, 0.0, 0.0);
            Vec3 away2d = new Vec3(away.x, 0.0, away.z);
            double len = away2d.lengthSqr();
            if (len < 0.0001) away2d = new Vec3(1.0, 0.0, 0.0);

            Vec3 dir = away2d.normalize();
            Vec3 target = villPos.add(dir.scale(FLEE_DISTANCE));

            vill.getNavigation().moveTo(target.x, target.y, target.z, FLEE_SPEED);
        } catch (Throwable ignored) {}
    }

    private void logNoThreat(String reason) {
        try {
            if (vill == null || vill.level() == null) return;
            long now = vill.level().getGameTime();
            if ((now - lastNoThreatLogAt) < 40L) return;
            lastNoThreatLogAt = now;
            VillagerOverhaul.LOG().info("[VillagerOverhaul] FLEE waiting (villager={} reason={})", vill.getUUID(), reason);
        } catch (Throwable ignored) {}
    }
}
