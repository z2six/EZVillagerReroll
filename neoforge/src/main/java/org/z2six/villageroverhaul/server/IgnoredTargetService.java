package org.z2six.villageroverhaul.server;

import net.minecraft.world.entity.Entity;

public final class IgnoredTargetService {

    private static final String KEY_IGNORED_BY_VILLAGERS = "ezvr_ignored_by_villagers";

    private IgnoredTargetService() {}

    public static boolean isIgnoredByVillagers(Entity entity) {
        try {
            if (entity == null) return false;
            return entity.getPersistentData().getBoolean(KEY_IGNORED_BY_VILLAGERS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setIgnoredByVillagers(Entity entity, boolean ignored) {
        try {
            if (entity == null) return false;
            boolean prev = isIgnoredByVillagers(entity);
            if (ignored) entity.getPersistentData().putBoolean(KEY_IGNORED_BY_VILLAGERS, true);
            else entity.getPersistentData().remove(KEY_IGNORED_BY_VILLAGERS);
            return prev != ignored;
        } catch (Throwable ignoredErr) {
            return false;
        }
    }

    public static boolean toggleIgnoredByVillagers(Entity entity) {
        try {
            if (entity == null) return false;
            boolean next = !isIgnoredByVillagers(entity);
            setIgnoredByVillagers(entity, next);
            return next;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
