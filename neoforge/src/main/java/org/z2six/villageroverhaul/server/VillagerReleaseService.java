package org.z2six.villageroverhaul.server;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.nbt.Tag;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class VillagerReleaseService {

    public static final String K_RELEASED_NO_RESPAWN = "ezvr_released_no_respawn";

    private static final String K_RELEASE_START = "ezvr_release_start";
    private static final String K_RELEASE_X = "ezvr_release_x";
    private static final String K_RELEASE_Y = "ezvr_release_y";
    private static final String K_RELEASE_Z = "ezvr_release_z";
    private static final int FADE_TICKS = 80;
    private static final int REMOVE_AFTER_TICKS = 100;
    private static final Map<UUID, ResourceKey<Level>> ACTIVE = new ConcurrentHashMap<>();
    private static final Set<UUID> SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService RELEASE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VillagerOverhaul Release Scheduler");
        t.setDaemon(true);
        return t;
    });

    private VillagerReleaseService() {}

    public static void beginRelease(Villager vill, ServerPlayer actor) {
        try {
            if (vill == null || !(vill.level() instanceof ServerLevel level)) return;
            if (isReleasing(vill)) {
                rememberActive(vill);
                scheduleNext(level.getServer(), vill.getUUID(), level.dimension());
                return;
            }

            CompoundTag pd = vill.getPersistentData();
            long now = level.getGameTime();
            pd.putBoolean(K_RELEASED_NO_RESPAWN, true);
            pd.putLong(K_RELEASE_START, now);
            pd.putDouble(K_RELEASE_X, vill.getX());
            pd.putDouble(K_RELEASE_Y, vill.getY());
            pd.putDouble(K_RELEASE_Z, vill.getZ());

            try { VillagerBrain.setUiPaused(vill, true); } catch (Throwable ignored) {}
            try { VillagerBrain.combatOff(vill); } catch (Throwable ignored) {}
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.setDeltaMovement(0.0, 0.0, 0.0); } catch (Throwable ignored) {}
            try { vill.setNoAi(true); } catch (Throwable ignored) {}
            try { vill.setInvulnerable(true); } catch (Throwable ignored) {}
            setReleaseAlpha(vill, 255);
            rememberActive(vill);
            scheduleNext(level.getServer(), vill.getUUID(), level.dimension());

            try {
                level.playSound(null, vill.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.NEUTRAL, 0.7F, 1.35F);
                level.sendParticles(ParticleTypes.ENCHANT, vill.getX(), vill.getY() + 0.9, vill.getZ(), 32, 0.35, 0.55, 0.35, 0.05);
            } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Release started by {} for villager={}",
                    actor == null ? "unknown" : actor.getGameProfile().getName(), vill.getUUID());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerReleaseService.beginRelease failed", t);
        }
    }

    public static boolean isReleasing(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag pd = vill.getPersistentData();
            return pd != null && pd.contains(K_RELEASE_START, Tag.TAG_LONG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isReleasedNoRespawn(Villager vill) {
        try {
            return vill != null && vill.getPersistentData().getBoolean(K_RELEASED_NO_RESPAWN);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void onEntityJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        try {
            if (e == null || e.getLevel() == null || e.getLevel().isClientSide()) return;
            if (e.getEntity() instanceof Villager vill && isReleasing(vill)) {
                rememberActive(vill);
                if (vill.level() instanceof ServerLevel level) {
                    scheduleNext(level.getServer(), vill.getUUID(), level.dimension());
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean tickVillager(ServerLevel level, Villager vill) {
        try {
            CompoundTag pd = vill.getPersistentData();
            long start = pd.getLong(K_RELEASE_START);
            int elapsed = (int) Math.max(0L, level.getGameTime() - start);

            double x = pd.contains(K_RELEASE_X, Tag.TAG_DOUBLE) ? pd.getDouble(K_RELEASE_X) : vill.getX();
            double y = pd.contains(K_RELEASE_Y, Tag.TAG_DOUBLE) ? pd.getDouble(K_RELEASE_Y) : vill.getY();
            double z = pd.contains(K_RELEASE_Z, Tag.TAG_DOUBLE) ? pd.getDouble(K_RELEASE_Z) : vill.getZ();

            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.setNoAi(true); } catch (Throwable ignored) {}
            try { vill.setInvulnerable(true); } catch (Throwable ignored) {}
            try { vill.teleportTo(x, y, z); } catch (Throwable ignored) {}
            try { vill.setDeltaMovement(0.0, 0.0, 0.0); } catch (Throwable ignored) {}
            try { VillagerBrain.setUiPaused(vill, true); } catch (Throwable ignored) {}

            int alpha = Math.max(0, Math.min(255, Math.round(255.0F * (1.0F - (elapsed / (float) FADE_TICKS)))));
            setReleaseAlpha(vill, alpha);

            if (elapsed % 8 == 0) {
                double t = elapsed / 8.0;
                double ox = Math.cos(t) * 0.35;
                double oz = Math.sin(t) * 0.35;
                level.sendParticles(ParticleTypes.PORTAL, x + ox, y + 0.9, z + oz, 4, 0.08, 0.18, 0.08, 0.02);
            }
            if (elapsed == FADE_TICKS / 2) {
                level.playSound(null, vill.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.8F, 0.75F);
            }

            if (elapsed >= REMOVE_AFTER_TICKS) {
                try {
                    level.sendParticles(ParticleTypes.POOF, x, y + 0.8, z, 24, 0.35, 0.45, 0.35, 0.02);
                    level.playSound(null, vill.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.5F, 1.55F);
                } catch (Throwable ignored) {}
                try { vill.remove(Entity.RemovalReason.DISCARDED); } catch (Throwable ignored) { vill.discard(); }
                return false;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerReleaseService.tickVillager failed (soft): {}", t.toString());
        }
        return true;
    }

    private static void rememberActive(Villager vill) {
        try {
            if (vill == null || !(vill.level() instanceof ServerLevel level)) return;
            ACTIVE.put(vill.getUUID(), level.dimension());
        } catch (Throwable ignored) {}
    }

    private static void scheduleNext(MinecraftServer server, UUID villagerUuid, ResourceKey<Level> dimension) {
        try {
            if (server == null || villagerUuid == null || dimension == null) return;
            if (!SCHEDULED.add(villagerUuid)) return;
            RELEASE_SCHEDULER.schedule(() -> {
                try {
                    server.execute(() -> stepScheduled(server, villagerUuid, dimension));
                } catch (Throwable ignored) {
                    SCHEDULED.remove(villagerUuid);
                }
            }, 50L, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SCHEDULED.remove(villagerUuid);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerReleaseService scheduleNext failed (soft): {}", t.toString());
        }
    }

    private static void stepScheduled(MinecraftServer server, UUID villagerUuid, ResourceKey<Level> dimension) {
        try {
            SCHEDULED.remove(villagerUuid);
            ResourceKey<Level> activeDimension = ACTIVE.get(villagerUuid);
            if (activeDimension == null) return;
            if (!activeDimension.equals(dimension)) dimension = activeDimension;

            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                ACTIVE.remove(villagerUuid);
                return;
            }

            Entity entity = level.getEntity(villagerUuid);
            if (!(entity instanceof Villager vill) || !isReleasing(vill)) {
                ACTIVE.remove(villagerUuid);
                return;
            }

            if (tickVillager(level, vill)) {
                scheduleNext(server, villagerUuid, dimension);
            } else {
                ACTIVE.remove(villagerUuid);
            }
        } catch (Throwable t) {
            SCHEDULED.remove(villagerUuid);
            ACTIVE.remove(villagerUuid);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerReleaseService scheduled step failed (soft): {}", t.toString());
        }
    }

    private static void setReleaseAlpha(Villager vill, int alpha) {
        try {
            if (vill instanceof VillagerOverhaulRenderAccess acc) {
                acc.ezvr$setReleaseAlpha((byte) Math.max(0, Math.min(255, alpha)));
            }
        } catch (Throwable ignored) {}
    }
}
