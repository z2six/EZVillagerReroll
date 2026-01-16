// neoforge\src\main\java\org\z2six\villageroverhaul\server\ai\VillagerIdleGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * "Idle" command:
 * - blocks navigation + horizontal movement
 * - does NOT claim LOOK, so vanilla can still rotate head if its look goals run
 * - adds gentle "idle scanning" (small turns + head look) to feel alive
 */
public final class VillagerIdleGoal extends Goal {

    private final Villager vill;

    // "natural" idle scanning
    private int nextLookChangeTicks = 0;
    private float targetYawDeg = 0.0f;

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

        // Initialize scan target so it doesn't snap on first tick
        try {
            targetYawDeg = vill.getYRot();
            nextLookChangeTicks = 10 + vill.getRandom().nextInt(30);
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        stopMovement();
        doIdleLookAround();
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

            // Extra belt-and-suspenders:
            try { vill.zza = 0.0f; } catch (Throwable ignored) {}
            try { vill.xxa = 0.0f; } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * Gentle "alive" behavior while IDLE:
     * - every so often, pick a new yaw target
     * - slowly rotate body toward it
     * - ask LookControl to look at a nearby point, producing head movement
     *
     * This does not claim LOOK flag, so vanilla look goals may still run.
     * Our calls are soft and low-frequency, so it tends to blend naturally.
     */
    private void doIdleLookAround() {
        try {
            if (vill == null) return;

            // If something else is forcing attention (hurt/target/etc), don't fight it.
            // (Villagers usually have no combat target, but this keeps it safe.)
            if (vill.getTarget() != null) return;

            if (nextLookChangeTicks-- <= 0) {
                // Pick a new yaw a bit left/right (avoid huge spins)
                float cur = vill.getYRot();
                float delta = (vill.getRandom().nextFloat() * 120.0f) - 60.0f; // -60..+60
                targetYawDeg = cur + delta;

                // Low-frequency: change every ~2–6 seconds
                nextLookChangeTicks = 40 + vill.getRandom().nextInt(80);
            }

            // Slowly rotate the BODY (small degrees per tick)
            float curYaw = vill.getYRot();
            float newYaw = Mth.approachDegrees(curYaw, targetYawDeg, 2.0f);
            vill.setYRot(newYaw);
            vill.yRotO = newYaw;

            // Ask LookControl to look at a point a few blocks away in the target direction.
            // This creates natural head turning and small up/down variance.
            double rad = Math.toRadians(targetYawDeg);
            double dist = 2.5 + vill.getRandom().nextDouble() * 2.5; // 2.5..5.0
            double dx = -Math.sin(rad) * dist;
            double dz =  Math.cos(rad) * dist;

            Vec3 pos = vill.position();
            double lookX = pos.x + dx;
            double lookZ = pos.z + dz;

            // Slight vertical variance around eye height
            double lookY = pos.y + vill.getEyeHeight() + (vill.getRandom().nextDouble() * 0.4 - 0.2);

            // These limits keep it subtle
            vill.getLookControl().setLookAt(lookX, lookY, lookZ, 30.0f, 30.0f);

        } catch (Throwable ignored) {}
    }
}
