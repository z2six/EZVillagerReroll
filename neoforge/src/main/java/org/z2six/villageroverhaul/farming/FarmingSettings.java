package org.z2six.villageroverhaul.farming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FarmingSettings {

    private static final String K_UPDATED_AT = "updatedAt";
    private static final String K_TIMEOUT_SECONDS = "timeoutSeconds";
    private static final String K_RETRY_SECONDS = "retryAfterSeconds";
    private static final String K_DEPOSIT_RULES = "depositRules";
    private static final String K_WITHDRAW_RULES = "withdrawRules";

    // Legacy (pre per-item thresholds)
    private static final String K_RULES_LEGACY = "rules"; // old per-item list (deposit-only)
    private static final String K_STACKS_LEGACY = "stacks"; // old global stacks (deposit-only)
    private static final String K_ITEMS_LEGACY = "items"; // old item list (deposit-only)

    private static final String K_RULE_ID = "id";
    private static final String K_RULE_STACKS = "stacks";
    private static final String K_RULE_KEEP = "keep";

    public long updatedAt = 0L;
    public int timeoutSeconds = 60;
    public int retryAfterSeconds = 60;
    public final List<ItemRule> depositRules = new ArrayList<>();
    public final List<ItemRule> withdrawRules = new ArrayList<>();

    public static final class ItemRule {
        public String itemId = "";
        public int stacksThreshold = 0; // trigger stacks
        public int keepStacks = 0;      // desired stacks to keep in villager inventory

        public ItemRule() {}

        public ItemRule(String itemId, int stacksThreshold) {
            this(itemId, stacksThreshold, 0);
        }

        public ItemRule(String itemId, int stacksThreshold, int keepStacks) {
            this.itemId = itemId == null ? "" : itemId;
            this.stacksThreshold = Math.max(0, stacksThreshold);
            this.keepStacks = Math.max(0, keepStacks);
        }
    }

    public static FarmingSettings fromTag(CompoundTag tag) {
        FarmingSettings s = new FarmingSettings();
        if (tag == null) return s;

        try {
            s.updatedAt = Math.max(0L, tag.getLong(K_UPDATED_AT));
        } catch (Throwable ignored) {
            s.updatedAt = 0L;
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

        s.depositRules.clear();
        s.withdrawRules.clear();

        // New format: per-action rules
        try {
            if (tag.contains(K_DEPOSIT_RULES, Tag.TAG_LIST)) {
                ListTag list = tag.getList(K_DEPOSIT_RULES, Tag.TAG_COMPOUND);
                int n = Math.min(512, list.size());
                for (int i = 0; i < n; i++) {
                    CompoundTag rt = list.getCompound(i);
                    if (rt == null) continue;
                    String raw = rt.getString(K_RULE_ID);
                    if (raw == null) continue;
                    String norm = raw.trim().toLowerCase(Locale.ROOT);
                    if (norm.isEmpty()) continue;
                    int stacks = 0;
                    try { stacks = Math.max(0, rt.getInt(K_RULE_STACKS)); } catch (Throwable ignored) { stacks = 0; }
                    int keep = 0;
                    try { keep = Math.max(0, rt.getInt(K_RULE_KEEP)); } catch (Throwable ignored) { keep = 0; }

                    boolean exists = false;
                    for (ItemRule r : s.depositRules) {
                        if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) { exists = true; break; }
                    }
                    if (exists) continue;

                    s.depositRules.add(new ItemRule(norm, stacks, keep));
                }
            }

            if (tag.contains(K_WITHDRAW_RULES, Tag.TAG_LIST)) {
                ListTag list = tag.getList(K_WITHDRAW_RULES, Tag.TAG_COMPOUND);
                int n = Math.min(512, list.size());
                for (int i = 0; i < n; i++) {
                    CompoundTag rt = list.getCompound(i);
                    if (rt == null) continue;
                    String raw = rt.getString(K_RULE_ID);
                    if (raw == null) continue;
                    String norm = raw.trim().toLowerCase(Locale.ROOT);
                    if (norm.isEmpty()) continue;
                    int stacks = 0;
                    try { stacks = Math.max(0, rt.getInt(K_RULE_STACKS)); } catch (Throwable ignored) { stacks = 0; }
                    int keep = 0;
                    try { keep = Math.max(0, rt.getInt(K_RULE_KEEP)); } catch (Throwable ignored) { keep = 0; }

                    boolean exists = false;
                    for (ItemRule r : s.withdrawRules) {
                        if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) { exists = true; break; }
                    }
                    if (exists) continue;

                    s.withdrawRules.add(new ItemRule(norm, stacks, keep));
                }
            }
        } catch (Throwable ignored) {}

        // Legacy format: per-item list (deposit-only)
        try {
            if (tag.contains(K_RULES_LEGACY, Tag.TAG_LIST)) {
                ListTag list = tag.getList(K_RULES_LEGACY, Tag.TAG_COMPOUND);
                int n = Math.min(512, list.size());
                for (int i = 0; i < n; i++) {
                    CompoundTag rt = list.getCompound(i);
                    if (rt == null) continue;
                    String raw = rt.getString(K_RULE_ID);
                    if (raw == null) continue;
                    String norm = raw.trim().toLowerCase(Locale.ROOT);
                    if (norm.isEmpty()) continue;

                    boolean exists = false;
                    for (ItemRule r : s.depositRules) {
                        if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) { exists = true; break; }
                    }
                    if (exists) continue;

                    int stacks = 0;
                    try { stacks = Math.max(0, rt.getInt(K_RULE_STACKS)); } catch (Throwable ignored) { stacks = 0; }
                    int keep = 0;
                    try { keep = Math.max(0, rt.getInt(K_RULE_KEEP)); } catch (Throwable ignored) { keep = 0; }
                    s.depositRules.add(new ItemRule(norm, stacks, keep));
                }
            }
        } catch (Throwable ignored) {}

        // Older legacy: global stacks + item list (deposit-only)
        try {
            int legacyStacks = 0;
            try { legacyStacks = Math.max(0, tag.getInt(K_STACKS_LEGACY)); } catch (Throwable ignored) { legacyStacks = 0; }

            if (tag.contains(K_ITEMS_LEGACY, Tag.TAG_LIST)) {
                ListTag list = tag.getList(K_ITEMS_LEGACY, Tag.TAG_STRING);
                int n = Math.min(512, list.size());
                for (int i = 0; i < n; i++) {
                    String raw = list.getString(i);
                    if (raw == null) continue;
                    String norm = raw.trim().toLowerCase(Locale.ROOT);
                    if (norm.isEmpty()) continue;

                    boolean exists = false;
                    for (ItemRule r : s.depositRules) {
                        if (r != null && r.itemId != null && r.itemId.equalsIgnoreCase(norm)) { exists = true; break; }
                    }
                    if (exists) continue;

                    s.depositRules.add(new ItemRule(norm, legacyStacks, 0));
                }
            }
        } catch (Throwable ignored) {}

        return s;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_UPDATED_AT, Math.max(0L, updatedAt));
        tag.putInt(K_TIMEOUT_SECONDS, Math.max(1, timeoutSeconds));
        tag.putInt(K_RETRY_SECONDS, Math.max(1, retryAfterSeconds));

        ListTag depositList = new ListTag();
        int n = Math.min(512, depositRules.size());
        for (int i = 0; i < n; i++) {
            ItemRule r = depositRules.get(i);
            if (r == null || r.itemId == null) continue;
            String norm = r.itemId.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) continue;

            CompoundTag rt = new CompoundTag();
            rt.putString(K_RULE_ID, norm);
            rt.putInt(K_RULE_STACKS, Math.max(0, r.stacksThreshold));
            rt.putInt(K_RULE_KEEP, Math.max(0, r.keepStacks));
            depositList.add(rt);
        }
        tag.put(K_DEPOSIT_RULES, depositList);

        ListTag withdrawList = new ListTag();
        int m = Math.min(512, withdrawRules.size());
        for (int i = 0; i < m; i++) {
            ItemRule r = withdrawRules.get(i);
            if (r == null || r.itemId == null) continue;
            String norm = r.itemId.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) continue;

            CompoundTag rt = new CompoundTag();
            rt.putString(K_RULE_ID, norm);
            rt.putInt(K_RULE_STACKS, Math.max(0, r.stacksThreshold));
            rt.putInt(K_RULE_KEEP, Math.max(0, r.keepStacks));
            withdrawList.add(rt);
        }
        tag.put(K_WITHDRAW_RULES, withdrawList);

        return tag;
    }
}
