package org.z2six.villageroverhaul.server.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * When HELP combat finishes, returns the villager to the position it was at when HELP started,
 * then restores the movement mode it had at that time (unless it was FOLLOW).
 */
public final class VillagerHelpReturnGoal extends Goal {

    private final Villager vill;
    private Vec3 target = null;
    private String dim = "";
    private VillagerBrain.Mode mode = VillagerBrain.Mode.NEUTRAL;

    private static final long RETURN_TIMEOUT_TICKS = 30L * 20L;

    // Final-approach assist (same concept as taught waypoint execution).
    private static final double ASSIST_NEAR_DIST2 = 2.2 * 2.2;
    private static final double ASSIST_SPEED = 0.55;
    private static final double ASSIST_MIN_VEL_SQR = 0.008 * 0.008;
    private static final double ASSIST_PUSH_PER_TICK = 0.035;

    public VillagerHelpReturnGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;
            if (!VillagerBrain.isHelpReturnActive(vill)) return false;
            if (VillagerBrain.isStorageActive(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;
            // Do not override active combat.
            if (VillagerBrain.shouldCombatActNow(vill)) return false;
            if (VillagerBrain.isCombatEngaged(vill)) return false;
            return true;
        } catch (Throwable ignored) {
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
            target = VillagerBrain.getHelpReturnPos(vill);
            dim = VillagerBrain.getHelpReturnDim(vill);
            mode = VillagerBrain.getHelpReturnMode(vill);
        } catch (Throwable ignored) {
            target = null;
            dim = "";
            mode = VillagerBrain.Mode.NEUTRAL;
        }
    }

    @Override
    public void tick() {
        try {
            if (vill == null) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;
            if (target == null) {
                VillagerBrain.clearHelpReturn(vill);
                return;
            }
            if (dim != null && !dim.isBlank()) {
                String curDim = String.valueOf(sl.dimension().location());
                if (!curDim.equals(dim)) {
                    VillagerBrain.clearHelpReturn(vill);
                    return;
                }
            }

            // Timeout: never get stuck forever trying to return to a precise spot.
            try {
                long now = sl.getGameTime();
                // Back-compat: if old villagers had return active without a timestamp, set one now.
                try { VillagerBrain.ensureHelpReturnSince(vill, now); } catch (Throwable ignored) {}
                long since = VillagerBrain.getHelpReturnSince(vill);
                if (since > 0L && now - since > RETURN_TIMEOUT_TICKS) {
                    try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                    VillagerBrain.clearHelpReturn(vill);
                    if (mode != VillagerBrain.Mode.FOLLOW) {
                        try { VillagerBrain.setMode(vill, mode); } catch (Throwable ignored) {}
                    }
                    return;
                }
            } catch (Throwable ignored) {}

            if (isAtTargetBlock(target)) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                VillagerBrain.clearHelpReturn(vill);
                // Restore the mode we had at help start (unless FOLLOW).
                if (mode != VillagerBrain.Mode.FOLLOW) {
                    try { VillagerBrain.setMode(vill, mode); } catch (Throwable ignored) {}
                }
                return;
            }

            // Look + walk towards the old position. Speed uses vanilla navigation scaling.
            try { vill.getLookControl().setLookAt(target.x, target.y, target.z, 30.0F, 30.0F); } catch (Throwable ignored) {}
            try { vill.getNavigation().moveTo(target.x, target.y, target.z, 0.6); } catch (Throwable ignored) {}
            if (vill.position().distanceToSqr(target) <= ASSIST_NEAR_DIST2) applyFinalApproachAssist(target);
        } catch (Throwable ignored) {}
    }

    private boolean isAtTargetBlock(Vec3 target) {
        try {
            if (target == null) return false;
            BlockPos tp = BlockPos.containing(target);
            BlockPos vp = vill.blockPosition();
            if (tp.getX() != vp.getX()) return false;
            if (tp.getZ() != vp.getZ()) return false;
            return Math.abs(tp.getY() - vp.getY()) <= 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyFinalApproachAssist(Vec3 target) {
        try {
            if (target == null) return;
            try { vill.getMoveControl().setWantedPosition(target.x, target.y, target.z, ASSIST_SPEED); } catch (Throwable ignored) {}

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

            double nx = vel.x * 0.35 + px;
            double nz = vel.z * 0.35 + pz;
            vill.setDeltaMovement(nx, vel.y, nz);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        target = null;
        dim = "";
        mode = VillagerBrain.Mode.NEUTRAL;
    }
}
