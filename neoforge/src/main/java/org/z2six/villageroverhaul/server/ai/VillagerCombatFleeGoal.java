// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatFleeGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.RecruitService;

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
    private long fleeUntilTick = 0L;
    private long lastThreatAt = 0L;
    private long nextRerouteAt = 0L;
    private long lastScanAt = 0L;

    public VillagerCombatFleeGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;

            // Combat should not act during FOLLOW (per your spec).
            if (VillagerBrain.getMode(vill) == VillagerBrain.Mode.FOLLOW) return false;

            return VillagerBrain.getCombatMode(vill) == VillagerBrain.CombatMode.FLEE;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatFleeGoal.canUse failed (soft): {}", t.toString());
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
            fleeUntilTick = 0L;
            lastThreatAt = 0L;
            nextRerouteAt = 0L;
            lastScanAt = 0L;
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
                    lastThreatAt = now;
                    fleeUntilTick = now + 20L * 30L;

                    if (VillagerOverhaul.LOG().isDebugEnabled()) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Flee threat set (villager={} attacker={})",
                                vill.getUUID(), attacker.getUUID());
                    }
                }
            }

            if (threatUuid == null) return;

            LivingEntity threat = findThreatByUuid(threatUuid);
            if (threat == null || !threat.isAlive()) {
                if (now - lastThreatAt > 40L) {
                    threatUuid = null;
                    vill.getNavigation().stop();
                    return;
                }
            }

            if (fleeUntilTick > 0L && now > fleeUntilTick) {
                threatUuid = null;
                vill.getNavigation().stop();
                return;
            }

            if (now >= nextRerouteAt) {
                nextRerouteAt = now + 5L;
                tryReroute(threat);
            }

            // Step 1: do nothing besides optional debug.
            if (!loggedActive && VillagerOverhaul.LOG().isDebugEnabled()) {
                loggedActive = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat goal active: FLEE (villager={}, mode={})",
                        vill == null ? "null" : vill.getUUID(),
                        vill == null ? "null" : VillagerBrain.getMode(vill).id);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatFleeGoal.tick failed (soft): {}", t.toString());
        }
    }

    @Override
    public void stop() {
        try {
            loggedActive = false;
            threatUuid = null;
            fleeUntilTick = 0L;
            lastThreatAt = 0L;
            nextRerouteAt = 0L;
            lastScanAt = 0L;
        } catch (Throwable ignored) {}
    }

    private LivingEntity findRecentAttacker(Villager vill) {
        try {
            if (vill == null || vill.level() == null) return null;

            UUID owner = null;
            try {
                owner = RecruitService.getRecruiterUuid(vill);
            } catch (Throwable ignored) {}

            AABB box = vill.getBoundingBox().inflate(26.0);
            List<LivingEntity> nearby = vill.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != null && e.isAlive());

            for (LivingEntity target : nearby) {
                if (target == vill) continue;

                LivingEntity attacker = target.getLastHurtByMob();
                if (attacker == null) continue;

                int hurtAt = target.getLastHurtByMobTimestamp();
                if ((vill.tickCount - hurtAt) > 40) continue;

                if (owner != null) {
                    if (owner.equals(attacker.getUUID())) continue;
                    if (owner.equals(target.getUUID())) continue;
                }

                if (attacker == vill) continue;
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
            Vec3 threatPos = (threat != null) ? threat.position() : null;

            Vec3 away2d;
            if (threatPos != null) {
                Vec3 away = villPos.subtract(threatPos);
                away2d = new Vec3(away.x, 0.0, away.z);
            } else {
                away2d = new Vec3(1.0, 0.0, 0.0);
            }

            double len = away2d.lengthSqr();
            if (len < 0.0001) {
                away2d = new Vec3(1.0, 0.0, 0.0);
            }

            Vec3 perp = new Vec3(-away2d.z, 0.0, away2d.x);
            if (vill.getRandom().nextBoolean()) {
                perp = perp.scale(-1.0);
            }

            Vec3 dir = perp.normalize();
            Vec3 target = villPos.add(dir.scale(10.0));

            vill.getNavigation().moveTo(target.x, target.y, target.z, 1.2);
        } catch (Throwable ignored) {}
    }
}
