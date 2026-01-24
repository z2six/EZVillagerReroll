package org.z2six.villageroverhaul.farming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FarmingSettings {

    private static final String K_UPDATED_AT = "updatedAt";
    private static final String K_STACKS = "stacks";
    private static final String K_TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String K_RETRY_SECONDS = "retryAfterSeconds";
    private static final String K_ITEMS = "items";

    public long updatedAt = 0L;
    public int stacksThreshold = 0;
    public int timeoutSeconds = 60;
    public int retryAfterSeconds = 60;
    public final List<String> itemIds = new ArrayList<>();

    public static FarmingSettings fromTag(CompoundTag tag) {
        FarmingSettings s = new FarmingSettings();
        if (tag == null) return s;

        try {
            s.updatedAt = Math.max(0L, tag.getLong(K_UPDATED_AT));
        } catch (Throwable ignored) {
            s.updatedAt = 0L;
        }

        try {
            s.stacksThreshold = Math.max(0, tag.getInt(K_STACKS));
        } catch (Throwable ignored) {
            s.stacksThreshold = 0;
        }

        try {
            if (tag.contains(K_TIMEOUT_SECONDS, Tag.TAG_INT)) {
                int t = tag.getInt(K_TIMEOUT_SECONDS);
                s.timeoutSeconds = Math.max(1, t);
            } else {
                s.timeoutSeconds = 60;
            }
        } catch (Throwable ignored) {
            s.timeoutSeconds = 60;
        }

        try {
            if (tag.contains(K_RETRY_SECONDS, Tag.TAG_INT)) {
                int t = tag.getInt(K_RETRY_SECONDS);
                s.retryAfterSeconds = Math.max(1, t);
            } else {
                s.retryAfterSeconds = 60;
            }
        } catch (Throwable ignored) {
            s.retryAfterSeconds = 60;
        }

        s.itemIds.clear();
        try {
            if (tag.contains(K_ITEMS, Tag.TAG_LIST)) {
                ListTag list = tag.getList(K_ITEMS, Tag.TAG_STRING);
                int n = Math.min(512, list.size());
                for (int i = 0; i < n; i++) {
                    String raw = list.getString(i);
                    if (raw == null) continue;
                    String norm = raw.trim().toLowerCase(Locale.ROOT);
                    if (norm.isEmpty()) continue;
                    if (!s.itemIds.contains(norm)) s.itemIds.add(norm);
                }
            }
        } catch (Throwable ignored) {}

        return s;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_UPDATED_AT, Math.max(0L, updatedAt));
        tag.putInt(K_STACKS, Math.max(0, stacksThreshold));
        tag.putInt(K_TIMEOUT_SECONDS, Math.max(1, timeoutSeconds));
        tag.putInt(K_RETRY_SECONDS, Math.max(1, retryAfterSeconds));

        ListTag list = new ListTag();
        int n = Math.min(512, itemIds.size());
        for (int i = 0; i < n; i++) {
            String raw = itemIds.get(i);
            if (raw == null) continue;
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) continue;
            list.add(net.minecraft.nbt.StringTag.valueOf(norm));
        }
        tag.put(K_ITEMS, list);
        return tag;
    }
}
