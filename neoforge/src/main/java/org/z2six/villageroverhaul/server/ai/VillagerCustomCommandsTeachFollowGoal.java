package org.z2six.villageroverhaul.server.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.server.CustomCommandsService;
import org.z2six.villageroverhaul.server.RecruitService;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Dedicated follow logic used only while teaching custom commands.
 */
public final class VillagerCustomCommandsTeachFollowGoal extends Goal {

    private final Villager vill;
    private ServerPlayer teacher;

    private static final double SPEED = 0.50;
    // More aggressive than vanilla follow: if we're more than ~1 block away, keep moving closer.
    private static final double STOP_DIST2 = 1.0 * 1.0;
    private static final double START_DIST2 = 1.2 * 1.2;

    public VillagerCustomCommandsTeachFollowGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel level)) return false;
            if (!RecruitService.isRecruited(vill)) return false;
            if (!CustomCommandsService.isVillagerTeaching(vill)) return false;

            UUID t = CustomCommandsService.getTeachingPlayer(vill);
            if (t == null) return false;
            teacher = level.getServer().getPlayerList().getPlayer(t);
            return teacher != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        try {
            if (vill == null) return false;
            if (!(vill.level() instanceof ServerLevel)) return false;
            if (!CustomCommandsService.isVillagerTeaching(vill)) return false;
            UUID t = CustomCommandsService.getTeachingPlayer(vill);
            if (t == null) return false;
            if (teacher == null || !teacher.getUUID().equals(t)) {
                teacher = ((ServerLevel) vill.level()).getServer().getPlayerList().getPlayer(t);
            }
            return teacher != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void start() {
        try {
            if (vill == null) return;
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        try {
            if (vill == null) return;
            teacher = null;
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            if (vill == null) return;
            if (teacher == null) {
                CustomCommandsService.clearTeachingState(vill);
                return;
            }

            double dist2 = vill.distanceToSqr(teacher);
            vill.getLookControl().setLookAt(teacher, 30.0F, 30.0F);

            if (dist2 <= STOP_DIST2) {
                vill.getNavigation().stop();
                return;
            }

            if (dist2 >= START_DIST2 || vill.getNavigation().isDone()) vill.getNavigation().moveTo(teacher, SPEED);

        } catch (Throwable ignored) {}
    }
}
