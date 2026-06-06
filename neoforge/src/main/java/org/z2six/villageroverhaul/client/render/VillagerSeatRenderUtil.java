package org.z2six.villageroverhaul.client.render;

import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerSeatCompat;

public final class VillagerSeatRenderUtil {

    private VillagerSeatRenderUtil() {}

    public static boolean shouldApplySeatPassengerPose(Villager villager) {
        try {
            if (villager == null || !villager.isPassenger()) return false;
            return VillagerSeatCompat.isLikelySeatEntity(villager.getVehicle());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
