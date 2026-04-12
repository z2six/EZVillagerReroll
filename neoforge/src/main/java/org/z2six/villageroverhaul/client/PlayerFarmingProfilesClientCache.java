package org.z2six.villageroverhaul.client;

import net.minecraft.nbt.CompoundTag;

public final class PlayerFarmingProfilesClientCache {

    private static volatile CompoundTag data;
    private static volatile long atMs = 0L;

    private PlayerFarmingProfilesClientCache() {}

    public static void set(CompoundTag tag) {
        data = tag == null ? new CompoundTag() : tag.copy();
        atMs = System.currentTimeMillis();
    }

    public static CompoundTag get() {
        return data == null ? null : data.copy();
    }

    public static long getAgeMs() {
        long at = atMs;
        return at <= 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - at);
    }
}
