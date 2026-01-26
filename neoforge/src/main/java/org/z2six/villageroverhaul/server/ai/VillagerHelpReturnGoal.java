package org.z2six.villageroverhaul.server.ai;

import net.minecraft.server.level.ServerLevel;
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

            double d2 = vill.position().distanceToSqr(target);
            if (d2 <= 1.2 * 1.2) {
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
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        target = null;
        dim = "";
        mode = VillagerBrain.Mode.NEUTRAL;
    }
}

