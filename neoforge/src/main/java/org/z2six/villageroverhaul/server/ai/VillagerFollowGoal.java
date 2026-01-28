// neoforge\src\main\java\org\z2six\villageroverhaul\server\ai\VillagerFollowGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import java.util.EnumSet;
import java.util.UUID;

/**
 * "Follow" command:
 * - villager follows the player that issued the command
 * - continues until mode changes away from FOLLOW
 */
public final class VillagerFollowGoal extends Goal {

    private final Villager vill;

    // Tuning
    private static final double SPEED = 0.50;           // path speed
    private static final double STOP_DIST = 2.0;        // stop when within this many blocks
    private static final double START_DIST = 3.0;       // start moving when farther than this
    private static final int RECALC_PATH_EVERY_TICKS = 2;

    private int recalcCooldown = 0;

    public VillagerFollowGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (vill == null) return false;
        if (VillagerBrain.isUiPaused(vill)) return false;
        if (VillagerBrain.isStorageActive(vill)) return false;
        if (VillagerBrain.isManualFarmingActive(vill)) return false;
        if (VillagerBrain.getMode(vill) != VillagerBrain.Mode.FOLLOW) return false;

        ServerPlayer target = getTargetPlayer();
        return target != null && target.isAlive();
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
            ServerPlayer target = getTargetPlayer();
            if (target == null) {
                // If target vanished, go NEUTRAL to avoid "stuck follow" state.
                VillagerBrain.setMode(vill, VillagerBrain.Mode.NEUTRAL);
                return;
            }

            double dist = vill.distanceTo(target);

            // Close enough: stop pathing
            if (dist <= STOP_DIST) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                return;
            }

            // Water fallback: vanilla navigation can fail badly in water; push a natural swim velocity toward the player.
            if (vill.isInWaterOrBubble()) {
                try {
                    // Prefer navigation so the villager can still find a way OUT of water (like vanilla NEUTRAL does).
                    // Only add a small velocity assist if nav looks stalled.
                    boolean navDone = false;
                    try { navDone = vill.getNavigation().isDone(); } catch (Throwable ignored) { navDone = false; }
                    try { vill.getNavigation().moveTo(target, SPEED); } catch (Throwable ignored) {}

                    Vec3 pos = vill.position();
                    Vec3 tpos = target.position();
                    double dx = tpos.x - pos.x;
                    double dz = tpos.z - pos.z;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (navDone && len > 1.0e-4) {
                        double ax = (dx / len) * 0.08;
                        double az = (dz / len) * 0.08;
                        Vec3 vel = vill.getDeltaMovement();
                        double nx = vel.x * 0.80 + ax;
                        double nz = vel.z * 0.80 + az;
                        vill.setDeltaMovement(nx, vel.y, nz);
                    }
                    try {
                        vill.getLookControl().setLookAt(target, 30.0F, 30.0F);
                        vill.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
                        vill.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new EntityTracker(target, false), (float) SPEED, (int) STOP_DIST));
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}
                return;
            }

            // Only recalc path periodically (cheaper)
            if (recalcCooldown > 0) {
                recalcCooldown--;
                return;
            }
            recalcCooldown = RECALC_PATH_EVERY_TICKS;

            // Refresh path frequently so it “walks” smoothly instead of waiting/catching up
            vill.getNavigation().moveTo(target, SPEED);

            // If far enough, path toward player
            if (dist >= START_DIST) {
                vill.getNavigation().moveTo(target, SPEED);
            }

        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
    }

    private ServerPlayer getTargetPlayer() {
        try {
            if (!(vill.level() instanceof ServerLevel sl)) return null;

            UUID u = VillagerBrain.getFollowPlayer(vill);
            if (u == null) return null;

            return sl.getServer().getPlayerList().getPlayer(u);
        } catch (Throwable t) {
            return null;
        }
    }
}
