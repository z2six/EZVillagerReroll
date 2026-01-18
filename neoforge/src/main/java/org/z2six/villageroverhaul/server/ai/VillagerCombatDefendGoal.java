// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ai/VillagerCombatDefendGoal.java
package org.z2six.villageroverhaul.server.ai;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.EnumSet;

/**
 * Combat module: DEFEND (activation only in Step 1).
 *
 * IMPORTANT (Step 1 behavior):
 * - Does NOT claim MOVE/JUMP/LOOK flags (so it will not interfere with existing movement AI yet).
 * - Only indicates "this mode is active" and provides a safe hook point for Step 3 AI.
 */
public final class VillagerCombatDefendGoal extends Goal {

    private final Villager vill;
    private boolean loggedActive = false;

    public VillagerCombatDefendGoal(Villager vill) {
        this.vill = vill;
        // No flags yet (do not interfere with movement until combat director exists).
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        try {
            if (vill == null) return false;
            if (vill.level() == null || vill.level().isClientSide()) return false;

            if (!VillagerBrain.shouldCombatActNow(vill)) return false;
            if (VillagerBrain.isUiPaused(vill)) return false;

            return VillagerBrain.getCombatMode(vill) == VillagerBrain.CombatMode.DEFEND;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDefendGoal.canUse failed (soft): {}", t.toString());
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
            loggedActive = false;
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        try {
            // Step 1: do nothing besides optional debug.
            if (!loggedActive && VillagerOverhaul.LOG().isDebugEnabled()) {
                loggedActive = true;
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Combat goal active: DEFEND (villager={}, mode={})",
                        vill == null ? "null" : vill.getUUID(),
                        vill == null ? "null" : VillagerBrain.getMode(vill).id);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatDefendGoal.tick failed (soft): {}", t.toString());
        }
    }

    @Override
    public void stop() {
        try {
            loggedActive = false;
        } catch (Throwable ignored) {}
    }
}
