package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

public final class VillagerNameStateService {
    public static final String TAG_ROOT = "ezvr_name";

    private static final String TAG_VERSION = "v";
    private static final int DATA_VERSION = 1;
    private static final String TAG_TRACKED = "tracked";
    private static final String TAG_FIRST = "first";
    private static final String TAG_LAST = "last";

    private VillagerNameStateService() {
    }

    public static void assignGeneratedName(Villager villager) {
        if (villager == null) {
            return;
        }
        int genderId = VillagerGenderService.ensureAssigned(villager);
        applyTrackedName(villager, VillagerNameGenerator.createFirstName(villager.getUUID(), genderId), VillagerNameGenerator.createLastName(villager.getUUID()));
    }

    public static void assignGeneratedName(Villager villager, String lastName) {
        if (villager == null) {
            return;
        }
        int genderId = VillagerGenderService.ensureAssigned(villager);
        applyTrackedName(villager, VillagerNameGenerator.createFirstName(villager.getUUID(), genderId), lastName);
    }

    public static void applyTrackedName(Villager villager, String firstName, String lastName) {
        if (villager == null || isBlank(firstName) || isBlank(lastName)) {
            return;
        }

        String normalizedFirst = firstName.trim();
        String normalizedLast = lastName.trim();
        villager.setCustomName(Component.literal(VillagerNameGenerator.createName(normalizedFirst, normalizedLast)));
        villager.setCustomNameVisible(true);
        storeTrackedName(villager, normalizedFirst, normalizedLast);
    }

    public static boolean tryAdoptExistingName(Villager villager) {
        if (villager == null || isTracked(villager)) {
            return villager != null && isTracked(villager);
        }

        Component customName = villager.getCustomName();
        if (customName == null) {
            return false;
        }

        String rawName = customName.getString();
        String firstName = extractFirstName(rawName);
        String lastName = VillagerNameGenerator.extractLastName(rawName);
        if (isBlank(firstName) || isBlank(lastName)) {
            return false;
        }

        int genderId = VillagerGenderService.ensureAssigned(villager);
        String expectedFirstName = VillagerNameGenerator.createFirstName(villager.getUUID(), genderId);
        if (!expectedFirstName.equals(firstName)) {
            return false;
        }

        storeTrackedName(villager, firstName, lastName);
        return true;
    }

    public static boolean isTracked(Villager villager) {
        CompoundTag root = getRoot(villager);
        return root != null
                && root.getBoolean(TAG_TRACKED)
                && root.contains(TAG_FIRST, CompoundTag.TAG_STRING)
                && root.contains(TAG_LAST, CompoundTag.TAG_STRING)
                && !root.getString(TAG_FIRST).isBlank()
                && !root.getString(TAG_LAST).isBlank();
    }

    @Nullable
    public static String getTrackedFirstName(Villager villager) {
        CompoundTag root = getRoot(villager);
        if (root == null || !root.contains(TAG_FIRST, CompoundTag.TAG_STRING)) {
            return null;
        }
        String value = root.getString(TAG_FIRST).trim();
        return value.isEmpty() ? null : value;
    }

    @Nullable
    public static String getTrackedLastName(Villager villager) {
        CompoundTag root = getRoot(villager);
        if (root == null || !root.contains(TAG_LAST, CompoundTag.TAG_STRING)) {
            return null;
        }
        String value = root.getString(TAG_LAST).trim();
        return value.isEmpty() ? null : value;
    }

    @Nullable
    public static String getTrackedFullName(Villager villager) {
        String first = getTrackedFirstName(villager);
        String last = getTrackedLastName(villager);
        if (first == null || last == null) {
            return null;
        }
        return VillagerNameGenerator.createName(first, last);
    }

    @Nullable
    private static CompoundTag getRoot(Villager villager) {
        if (villager == null) {
            return null;
        }
        CompoundTag pd = villager.getPersistentData();
        if (pd == null || !pd.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        return pd.getCompound(TAG_ROOT);
    }

    private static CompoundTag getOrCreateRoot(Villager villager) {
        CompoundTag pd = villager.getPersistentData();
        if (pd.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
            return pd.getCompound(TAG_ROOT);
        }
        CompoundTag root = new CompoundTag();
        pd.put(TAG_ROOT, root);
        return root;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static void storeTrackedName(Villager villager, String firstName, String lastName) {
        CompoundTag root = getOrCreateRoot(villager);
        root.putInt(TAG_VERSION, DATA_VERSION);
        root.putBoolean(TAG_TRACKED, true);
        root.putString(TAG_FIRST, firstName);
        root.putString(TAG_LAST, lastName);
        villager.getPersistentData().put(TAG_ROOT, root);
    }

    @Nullable
    private static String extractFirstName(@Nullable String fullName) {
        if (fullName == null) {
            return null;
        }
        int separator = fullName.indexOf(' ');
        if (separator <= 0) {
            return null;
        }
        String firstName = fullName.substring(0, separator).trim();
        return firstName.isEmpty() ? null : firstName;
    }
}
