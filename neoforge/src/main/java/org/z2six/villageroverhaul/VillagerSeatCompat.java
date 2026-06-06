package org.z2six.villageroverhaul;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

public final class VillagerSeatCompat {

    public static final double PLAYER_VEHICLE_ATTACHMENT_Y = 0.6D;

    private VillagerSeatCompat() {}

    public static boolean isLikelySeatEntity(Entity entity) {
        try {
            if (entity == null) return false;
            return isLikelySeatEntityName(entityTypeId(entity), entity.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isLikelySeatEntityName(String entityId, String className) {
        String haystack = ((entityId == null ? "" : entityId) + " " + (className == null ? "" : className)).toLowerCase(Locale.ROOT);
        return haystack.contains("seat")
                || haystack.contains("chair")
                || haystack.contains("sittable")
                || haystack.contains("sitting");
    }

    public static double adjustSeatVehicleAttachmentY(double vanillaAttachmentY) {
        return Math.max(vanillaAttachmentY, PLAYER_VEHICLE_ATTACHMENT_Y);
    }

    private static String entityTypeId(Entity entity) {
        try {
            if (entity == null) return "";
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return id == null ? String.valueOf(entity.getType()) : id.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
