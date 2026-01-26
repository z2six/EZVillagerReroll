package org.z2six.villageroverhaul.client;

import net.minecraft.nbt.CompoundTag;

public final class PlayerChatCommandsClientCache {
    private static volatile CompoundTag lastCfg;

    private PlayerChatCommandsClientCache() {}

    public static void put(CompoundTag tag) {
        lastCfg = tag == null ? new CompoundTag() : tag.copy();
    }

    public static CompoundTag consume() {
        CompoundTag t = lastCfg;
        lastCfg = null;
        return t;
    }
}

