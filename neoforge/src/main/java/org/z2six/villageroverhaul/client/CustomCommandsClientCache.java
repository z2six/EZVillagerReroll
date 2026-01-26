package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomCommandsClientCache {
    private static final Map<Integer, CompoundTag> TEACH_SESSION = new ConcurrentHashMap<>();
    private static final Map<Integer, CompoundTag> LIST_DATA = new ConcurrentHashMap<>();
    private static final Map<String, CompoundTag> DETAIL_DATA = new ConcurrentHashMap<>();

    private static volatile PendingChestRulesOpen pendingChestRules;

    private CustomCommandsClientCache() {}

    public static void putTeachSession(int villagerEntityId, CompoundTag tag) {
        if (villagerEntityId <= 0) return;
        TEACH_SESSION.put(villagerEntityId, tag == null ? new CompoundTag() : tag.copy());
    }

    public static CompoundTag consumeTeachSession(int villagerEntityId) {
        if (villagerEntityId <= 0) return null;
        return TEACH_SESSION.remove(villagerEntityId);
    }

    public static void putList(int villagerEntityId, CompoundTag tag) {
        if (villagerEntityId <= 0) return;
        LIST_DATA.put(villagerEntityId, tag == null ? new CompoundTag() : tag.copy());
    }

    public static CompoundTag consumeList(int villagerEntityId) {
        if (villagerEntityId <= 0) return null;
        return LIST_DATA.remove(villagerEntityId);
    }

    public static void putDetail(int villagerEntityId, int index, CompoundTag tag) {
        if (villagerEntityId <= 0) return;
        DETAIL_DATA.put(villagerEntityId + ":" + index, tag == null ? new CompoundTag() : tag.copy());
    }

    public static CompoundTag consumeDetail(int villagerEntityId, int index) {
        if (villagerEntityId <= 0) return null;
        return DETAIL_DATA.remove(villagerEntityId + ":" + index);
    }

    public static void requestOpenChestRules(int villagerEntityId, int stepIndex, int kind) {
        if (villagerEntityId <= 0) return;
        pendingChestRules = new PendingChestRulesOpen(villagerEntityId, stepIndex, kind, System.currentTimeMillis());
    }

    public static void clientTick() {
        try {
            PendingChestRulesOpen p = pendingChestRules;
            if (p == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            long age = System.currentTimeMillis() - p.createdAtMs;
            if (age > 5000L) {
                pendingChestRules = null;
                return;
            }

            Screen s = mc.screen;
            if (s instanceof AbstractContainerScreen) {
                // If a chest UI is open, close it so our editor can take focus.
                mc.setScreen(null);
                return;
            }

            if (s == null) {
                pendingChestRules = null;
                mc.setScreen(new CustomCommandsChestItemRulesEditorScreen(p.villagerEntityId, p.stepIndex, p.kind));
            }
        } catch (Throwable ignored) {}
    }

    private record PendingChestRulesOpen(int villagerEntityId, int stepIndex, int kind, long createdAtMs) {}
}
