package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class VillagerGenderService {

    public static final int GENDER_UNKNOWN = -1;
    public static final int GENDER_MALE = 0;
    public static final int GENDER_FEMALE = 1;

    public static final String SYMBOL_MALE = "\u2642";
    public static final String SYMBOL_FEMALE = "\u2640";

    private static final String TAG_ROOT = "ezvr_gender";
    private static final String TAG_VERSION = "v";
    private static final String TAG_GENDER = "gender";
    private static final int DATA_VERSION = 1;

    private VillagerGenderService() {}

    public static int ensureAssigned(@Nullable Villager villager) {
        try {
            if (villager == null) return GENDER_UNKNOWN;

            CompoundTag pd = villager.getPersistentData();
            CompoundTag root = getOrCreateRoot(pd);

            int existing = readGenderId(root);
            if (existing != GENDER_UNKNOWN) {
                return existing;
            }

            int assigned = villager.getRandom().nextBoolean() ? GENDER_MALE : GENDER_FEMALE;
            root.putInt(TAG_VERSION, DATA_VERSION);
            root.putInt(TAG_GENDER, assigned);
            pd.put(TAG_ROOT, root);

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Assigned villager gender: villager={} gender={}",
                    villager.getUUID(),
                    displayNameForId(assigned)
            );
            return assigned;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerGenderService.ensureAssigned failed (soft): {}", t.toString());
            return GENDER_UNKNOWN;
        }
    }

    public static int getGenderId(@Nullable Villager villager) {
        try {
            if (villager == null) return GENDER_UNKNOWN;
            return getGenderIdFromPersistentData(villager.getPersistentData());
        } catch (Throwable ignored) {
            return GENDER_UNKNOWN;
        }
    }

    public static int getGenderIdFromPersistentData(@Nullable CompoundTag persistentData) {
        try {
            if (persistentData == null || !persistentData.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                return GENDER_UNKNOWN;
            }
            return readGenderId(persistentData.getCompound(TAG_ROOT));
        } catch (Throwable ignored) {
            return GENDER_UNKNOWN;
        }
    }

    public static boolean isCompatibleBreedingPair(@Nullable Villager left, @Nullable Villager right) {
        try {
            if (left == null || right == null) return false;
            int a = ensureAssigned(left);
            int b = ensureAssigned(right);
            return (a == GENDER_MALE && b == GENDER_FEMALE) || (a == GENDER_FEMALE && b == GENDER_MALE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    public static Villager resolveMaleParent(@Nullable Villager parentA, @Nullable Villager parentB) {
        try {
            if (parentA == null || parentB == null) return null;
            int a = ensureAssigned(parentA);
            int b = ensureAssigned(parentB);
            if (a == GENDER_MALE && b == GENDER_FEMALE) return parentA;
            if (b == GENDER_MALE && a == GENDER_FEMALE) return parentB;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static Villager resolveFemaleParent(@Nullable Villager parentA, @Nullable Villager parentB) {
        try {
            if (parentA == null || parentB == null) return null;
            int a = ensureAssigned(parentA);
            int b = ensureAssigned(parentB);
            if (a == GENDER_FEMALE && b == GENDER_MALE) return parentA;
            if (b == GENDER_FEMALE && a == GENDER_MALE) return parentB;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String symbolForId(int genderId) {
        return switch (genderId) {
            case GENDER_MALE -> SYMBOL_MALE;
            case GENDER_FEMALE -> SYMBOL_FEMALE;
            default -> "?";
        };
    }

    public static String displayNameForId(int genderId) {
        return switch (genderId) {
            case GENDER_MALE -> "male";
            case GENDER_FEMALE -> "female";
            default -> "unknown";
        };
    }

    private static CompoundTag getOrCreateRoot(CompoundTag persistentData) {
        if (persistentData.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
            return persistentData.getCompound(TAG_ROOT);
        }
        CompoundTag root = new CompoundTag();
        persistentData.put(TAG_ROOT, root);
        return root;
    }

    private static int readGenderId(CompoundTag root) {
        try {
            if (root == null || !root.contains(TAG_GENDER, CompoundTag.TAG_INT)) return GENDER_UNKNOWN;
            int id = root.getInt(TAG_GENDER);
            return (id == GENDER_MALE || id == GENDER_FEMALE) ? id : GENDER_UNKNOWN;
        } catch (Throwable ignored) {
            return GENDER_UNKNOWN;
        }
    }
}
