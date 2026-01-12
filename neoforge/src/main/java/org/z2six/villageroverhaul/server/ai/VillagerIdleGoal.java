// neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerIdleGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * "Idle" command:
 * - blocks navigation + horizontal movement
 * - does NOT claim LOOK, so vanilla can still rotate head if its look goals run
 */
public final class VillagerIdleGoal extends Goal {

    private final Villager vill;

    public VillagerIdleGoal(Villager vill) {
        this.vill = vill;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return vill != null && VillagerBrain.getMode(vill) == VillagerBrain.Mode.IDLE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        stopMovement();
    }

    @Override
    public void tick() {
        stopMovement();
    }

    @Override
    public void stop() {
        // When leaving IDLE, don't “force” anything; vanilla will resume naturally.
    }

    private void stopMovement() {
        try {
            vill.getNavigation().stop();

            // Keep vertical velocity (gravity / knockback), kill horizontal.
            Vec3 v = vill.getDeltaMovement();
            vill.setDeltaMovement(0.0, v.y, 0.0);

            // Extra belt-and-suspenders (these fields exist on Mob in modern MC):
            try { vill.zza = 0.0f; } catch (Throwable ignored) {}
            try { vill.xxa = 0.0f; } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
}
