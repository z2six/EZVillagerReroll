// VillagerPatrolGoal.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerPatrolGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.EnumSet;
import java.util.List;

/**
 * PATROL:
 * - walks waypoint list stored on villager persistent data
 * - supports CIRCULAR and LINEAR (ping-pong) behavior
 * - patrol data persists even if mode changes; this goal only runs when Mode == PATROL
 */
public final class VillagerPatrolGoal extends Goal {

    private final Villager vill;

    private static final double SPEED = 0.55;
    private static final double ARRIVE_DIST = 1.6;
    private static final int RECALC_PATH_EVERY_TICKS = 5;

    private int recalcCooldown = 0;

    public VillagerPatrolGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (vill == null) return false;
        if (vill.level().isClientSide()) return false;
        if (!(vill.level() instanceof ServerLevel)) return false;

        if (VillagerBrain.getMode(vill) != VillagerBrain.Mode.PATROL) return false;

        if (!VillagerBrain.hasFinalizedPatrol(vill)) return false;

        return VillagerBrain.getPatrolWaypointCount(vill) >= 2;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        recalcCooldown = 0;
    }

    @Override
    public void tick() {
        try {
            if (!canUse()) return;

            List<Vec3> waypoints = VillagerBrain.getPatrolWaypoints(vill);
            if (waypoints == null || waypoints.size() < 2) return;

            int n = waypoints.size();
            int idx = VillagerBrain.getPatrolIndex(vill);
            int dir = VillagerBrain.getPatrolDir(vill);

            if (idx < 0) idx = 0;
            if (idx >= n) idx = n - 1;
            if (dir == 0) dir = 1;

            Vec3 target = waypoints.get(idx);
            if (target == null) return;

            double dist = vill.position().distanceTo(target);

            if (dist <= ARRIVE_DIST) {
                advance(n);
                return;
            }

            if (recalcCooldown > 0) {
                recalcCooldown--;
                return;
            }
            recalcCooldown = RECALC_PATH_EVERY_TICKS;

            vill.getNavigation().moveTo(target.x, target.y, target.z, SPEED);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerPatrolGoal tick failed (soft): {}", t.toString());
        }
    }

    private void advance(int n) {
        try {
            VillagerBrain.PatrolRouteType type = VillagerBrain.getPatrolRouteType(vill);

            int idx = VillagerBrain.getPatrolIndex(vill);
            int dir = VillagerBrain.getPatrolDir(vill);
            if (dir == 0) dir = 1;

            if (type == VillagerBrain.PatrolRouteType.CIRCULAR) {
                idx = (idx + 1) % n;
                VillagerBrain.setPatrolIndex(vill, idx);
                VillagerBrain.setPatrolDir(vill, 1);
                return;
            }

            // LINEAR ping-pong:
            // 0 1 2 3 2 1 0 1 ...
            int next = idx + dir;

            if (next >= n) {
                dir = -1;
                next = Math.max(0, n - 2);
            } else if (next < 0) {
                dir = 1;
                next = Math.min(n - 1, 1);
            }

            VillagerBrain.setPatrolDir(vill, dir);
            VillagerBrain.setPatrolIndex(vill, next);

        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
    }
}
