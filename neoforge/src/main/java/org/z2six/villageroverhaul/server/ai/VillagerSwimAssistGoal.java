package org.z2six.villageroverhaul.server.ai;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.server.RecruitService;

/**
 * Basic swimming/buoyancy assist for recruited villagers.
 *
 * Vanilla villagers can sink and drown; this goal provides gentle buoyancy so they float up for air.
 * It intentionally uses NO goal flags so it can run alongside other movement/combat goals.
 */
public final class VillagerSwimAssistGoal extends Goal {

    private final Villager vill;

    private static final double MAX_UPWARD_VEL = 0.18;
    private static final double UPWARD_ACCEL_EYE_IN_WATER = 0.060;
    private static final double UPWARD_ACCEL_BODY_IN_WATER = 0.018;

    public VillagerSwimAssistGoal(Villager vill) {
        this.vill = vill;
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;
            if (!RecruitService.isRecruited(vill)) return false;
            return vill.isInWaterOrBubble();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        try {
            if (vill == null) return;
            if (!vill.isInWaterOrBubble()) return;

            boolean eyeInWater = false;
            try { eyeInWater = vill.isEyeInFluid(FluidTags.WATER); } catch (Throwable ignored) { eyeInWater = false; }

            Vec3 vel = vill.getDeltaMovement();
            double ay = eyeInWater ? UPWARD_ACCEL_EYE_IN_WATER : UPWARD_ACCEL_BODY_IN_WATER;

            // Only push up if we are sinking or still. If already going up quickly, don't fight it.
            if (vel.y < 0.06) {
                double ny = Math.min(MAX_UPWARD_VEL, vel.y + ay);
                vill.setDeltaMovement(vel.x, ny, vel.z);
            }
        } catch (Throwable ignored) {}
    }
}

