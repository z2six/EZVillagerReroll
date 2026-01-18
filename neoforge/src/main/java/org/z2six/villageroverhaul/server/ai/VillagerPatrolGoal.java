// neoforge\src\main\java\org\z2six\villageroverhaul\server\ai\VillagerPatrolGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.EnumSet;
import java.util.List;

public final class VillagerPatrolGoal extends Goal {

    private final Villager vill;

    private static final double SPEED = 0.35;
    private static final int RECALC_PATH_EVERY_TICKS = 5;

    // Final approach assist:
    // When close, villagers often decelerate and stop short. We keep pressure on until they ENTER the target block.
    private static final double ASSIST_RADIUS = 2.25;              // start assisting within 2.25 blocks
    private static final double ASSIST_RADIUS_SQR = ASSIST_RADIUS * ASSIST_RADIUS;

    private static final double ASSIST_SPEED = 0.65;               // slightly higher than cruise
    private static final double ASSIST_PUSH_PER_TICK = 0.18;        // horizontal push magnitude
    private static final double ASSIST_MIN_VEL_SQR = 0.0006;        // if horizontal vel below this, we apply push

    // Stuck detection (movement-based)
    private static final int STUCK_CHECK_EVERY_TICKS = 10; // 1s
    private static final int STUCK_MAX_TICKS = 100;        // 10s
    private static final double STUCK_MOVE_EPS = 0.02;     // moved < 0.02 blocks in 1s => considered not moving
    private static final double STUCK_MOVE_EPS_SQR = STUCK_MOVE_EPS * STUCK_MOVE_EPS;

    private int recalcCooldown = 0;

    private int stuckCheckCooldown = 0;
    private int stuckTicks = 0;
    private Vec3 lastPos = null;

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
        if (VillagerBrain.isUiPaused(vill)) return false;

        // Pauses automatically while merchant menu open (via VillagerBrain.isPatrolPaused change)
        if (VillagerBrain.isPatrolPaused(vill)) return false;

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

        stuckCheckCooldown = 0;
        stuckTicks = 0;
        lastPos = vill == null ? null : vill.position();
    }

    @Override
    public void tick() {
        try {
            if (!canUse()) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                return;
            }

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

            // STRICT arrival: must actually be inside the waypoint block (x/z match), y tolerance <= 1
            if (isInWaypointBlock(target)) {
                resetStuck();
                advance(n);
                recalcCooldown = 0;
                return;
            }

            Vec3 pos = vill.position();
            double distSqr = pos.distanceToSqr(target);

            boolean navDone = false;
            try { navDone = vill.getNavigation().isDone(); } catch (Throwable ignored) { navDone = false; }

            // If nav says done but we aren't in the waypoint block yet, we must keep pushing/repathing.
            if (navDone) {
                recalcCooldown = 0;
            }

            // Stuck detection
            if (tickStuck(n)) {
                recalcCooldown = 0;
                return;
            }

            // Final approach assist: only when close-ish and not yet in block.
            // This is the important part that prevents the "slowly stopping before the block" problem.
            if (distSqr <= ASSIST_RADIUS_SQR) {
                applyFinalApproachAssist(target);
            }

            if (recalcCooldown > 0) {
                recalcCooldown--;
                return;
            }
            recalcCooldown = RECALC_PATH_EVERY_TICKS;

            // Keep navigation engaged.
            try {
                vill.getNavigation().moveTo(target.x, target.y, target.z, SPEED);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerPatrolGoal tick failed (soft): {}", t.toString());
        }
    }

    private boolean isInWaypointBlock(Vec3 target) {
        try {
            if (target == null) return false;

            BlockPos wp = BlockPos.containing(target.x, target.y, target.z);
            BlockPos vp = vill.blockPosition();

            if (vp.getX() != wp.getX()) return false;
            if (vp.getZ() != wp.getZ()) return false;

            return Math.abs(vp.getY() - wp.getY()) <= 1;

        } catch (Throwable t) {
            return false;
        }
    }

    private void applyFinalApproachAssist(Vec3 target) {
        try {
            // 1) Tell move-control to keep wanting the exact point.
            // This helps prevent vanilla "give up" behavior near the end.
            try {
                vill.getMoveControl().setWantedPosition(target.x, target.y, target.z, ASSIST_SPEED);
            } catch (Throwable ignored) {}

            // 2) If horizontal speed is dying, add a small horizontal push toward target.
            Vec3 pos = vill.position();
            Vec3 vel = vill.getDeltaMovement();

            double hv2 = vel.x * vel.x + vel.z * vel.z;
            if (hv2 >= ASSIST_MIN_VEL_SQR) return;

            double dx = target.x - pos.x;
            double dz = target.z - pos.z;

            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0e-4) return;

            double px = (dx / len) * ASSIST_PUSH_PER_TICK;
            double pz = (dz / len) * ASSIST_PUSH_PER_TICK;

            // Blend instead of overwrite so we don't do jittery snapping.
            double nx = vel.x * 0.35 + px;
            double nz = vel.z * 0.35 + pz;

            vill.setDeltaMovement(nx, vel.y, nz);

        } catch (Throwable ignored) {}
    }

    private boolean tickStuck(int n) {
        try {
            if (stuckCheckCooldown > 0) {
                stuckCheckCooldown--;
                return false;
            }
            stuckCheckCooldown = STUCK_CHECK_EVERY_TICKS;

            Vec3 cur = vill.position();

            if (lastPos == null) {
                lastPos = cur;
                stuckTicks = 0;
                return false;
            }

            // Horizontal movement only (y can fluctuate due to steps)
            double dx = cur.x - lastPos.x;
            double dz = cur.z - lastPos.z;
            double movedSqr = dx * dx + dz * dz;

            lastPos = cur;

            if (movedSqr <= STUCK_MOVE_EPS_SQR) {
                stuckTicks += STUCK_CHECK_EVERY_TICKS;
            } else {
                stuckTicks = 0;
            }

            if (stuckTicks >= STUCK_MAX_TICKS) {
                resetStuck();
                advance(n);
                return true;
            }

            return false;

        } catch (Throwable t) {
            return false;
        }
    }

    private void resetStuck() {
        stuckCheckCooldown = 0;
        stuckTicks = 0;
        lastPos = vill == null ? null : vill.position();
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
