// neoforge\src\main\java\org\z2six\villageroverhaul\server\ai\VillagerPatrolSetupFollowGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;

import java.util.EnumSet;
import java.util.UUID;

/**
 * PATROL_SETUP:
 * - villager follows the player (like FOLLOW) but lives in its own goal class
 * - only active while Mode == PATROL_SETUP
 */
public final class VillagerPatrolSetupFollowGoal extends Goal {

    private final Villager vill;

    private static final double SPEED = 0.50;
    private static final double STOP_DIST = 2.0;
    private static final double START_DIST = 3.0;
    private static final int RECALC_PATH_EVERY_TICKS = 2;

    private int recalcCooldown = 0;

    public VillagerPatrolSetupFollowGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (vill == null) return false;
        if (VillagerBrain.isUiPaused(vill)) return false;
        if (VillagerBrain.getMode(vill) != VillagerBrain.Mode.PATROL_SETUP) return false;

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
                VillagerBrain.setMode(vill, VillagerBrain.Mode.NEUTRAL);
                return;
            }

            double dist = vill.distanceTo(target);

            if (dist <= STOP_DIST) {
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                return;
            }

            if (recalcCooldown > 0) {
                recalcCooldown--;
                return;
            }
            recalcCooldown = RECALC_PATH_EVERY_TICKS;

            if (dist >= START_DIST) {
                vill.getNavigation().moveTo(target, SPEED);
            } else {
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

            UUID u = VillagerBrain.getPatrolSetupOwner(vill);
            if (u == null) return null;

            return sl.getServer().getPlayerList().getPlayer(u);
        } catch (Throwable t) {
            return null;
        }
    }
}
