// neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerFollowGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
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
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (vill == null) return false;
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
                // If target vanished, go NATURAL to avoid "stuck follow" state.
                VillagerBrain.setMode(vill, VillagerBrain.Mode.NATURAL);
                return;
            }

            double dist = vill.distanceTo(target);

            // Close enough: stop pathing
            if (dist <= STOP_DIST) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
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
