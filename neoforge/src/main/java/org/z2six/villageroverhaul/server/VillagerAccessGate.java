// neoforge\src\main\java\org\z2six\villageroverhaul\server\VillagerAccessGate.java
package org.z2six.villageroverhaul.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.UUID;

public final class VillagerAccessGate {

    private VillagerAccessGate() {}

    public enum Result {
        NOT_RECRUITED,
        NOT_OWNER,
        OWNER
    }

    /**
     * One-liner gate:
     *   VillagerAccessGate.Result r = VillagerAccessGate.check(vill, player);
     */
    public static Result check(Entity vill, ServerPlayer player) {
        try {
            if (vill == null || player == null) return Result.NOT_OWNER;

            if (!RecruitService.isRecruited(vill)) {
                return Result.NOT_RECRUITED;
            }

            UUID owner = RecruitService.getRecruiterUuid(vill);
            if (owner == null) {
                // Safety-first: if recruited flag exists but owner missing, deny controls.
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerAccessGate: recruited but missing owner tag (villager={})", vill.getUUID());
                return Result.NOT_OWNER;
            }

            return owner.equals(player.getUUID()) ? Result.OWNER : Result.NOT_OWNER;

        } catch (Throwable t) {
            return Result.NOT_OWNER;
        }
    }

    public static boolean canUseControls(Entity vill, ServerPlayer player) {
        return check(vill, player) == Result.OWNER;
    }

    /** Info screen is always allowed per your spec. */
    public static boolean canViewInfo(Entity vill, ServerPlayer player) {
        return vill != null && player != null;
    }
}
