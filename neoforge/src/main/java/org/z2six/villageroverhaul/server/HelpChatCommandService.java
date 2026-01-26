package org.z2six.villageroverhaul.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player "Help" chat command support.
 *
 * Tracks a set of hostile targets per player while help is active. Villagers in {@code CombatMode.HELP}
 * query this service for the current target set.
 */
public final class HelpChatCommandService {

    private HelpChatCommandService() {}

    private static final long CONTEXT_TTL_TICKS = 20L * 30L; // 30s
    private static final long RALLY_TICKS = 20L * 30L; // 30s

    private static final class Session {
        final Set<UUID> targets = new HashSet<>();
        int lastHurtByTs = 0;
        int lastHurtMobTs = 0;

        UUID lastAttacked = null;
        long lastAttackedAt = 0L;
        UUID lastAttackedBy = null;
        long lastAttackedByAt = 0L;

        long rallyUntil = 0L;
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public static boolean isActive(UUID playerUuid) {
        Session s = playerUuid == null ? null : SESSIONS.get(playerUuid);
        return s != null && (!s.targets.isEmpty() || s.rallyUntil > 0L);
    }

    public static List<UUID> getTargets(UUID playerUuid) {
        try {
            Session s = playerUuid == null ? null : SESSIONS.get(playerUuid);
            if (s == null || s.targets.isEmpty()) return List.of();
            return new ArrayList<>(s.targets);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static void activateFromPlayerContext(ServerPlayer sp) {
        try {
            if (sp == null) return;
            UUID owner = sp.getUUID();
            Session s = SESSIONS.computeIfAbsent(owner, k -> new Session());

            long now = 0L;
            try { now = sp.level().getGameTime(); } catch (Throwable ignored) { now = 0L; }

            int hurtByTs = 0;
            int hurtMobTs = 0;
            LivingEntity hurtBy = null;
            LivingEntity hurtMob = null;
            try { hurtByTs = sp.getLastHurtByMobTimestamp(); } catch (Throwable ignored) { hurtByTs = 0; }
            try { hurtMobTs = sp.getLastHurtMobTimestamp(); } catch (Throwable ignored) { hurtMobTs = 0; }
            try { hurtBy = sp.getLastHurtByMob(); } catch (Throwable ignored) { hurtBy = null; }
            try { hurtMob = sp.getLastHurtMob(); } catch (Throwable ignored) { hurtMob = null; }

            s.lastHurtByTs = hurtByTs;
            s.lastHurtMobTs = hurtMobTs;

            // Prefer explicit tracked context (more reliable cross-mod), but keep vanilla fallback too.
            UUID chosen = null;
            long chosenAt = 0L;
            try {
                if (s.lastAttacked != null && (now - s.lastAttackedAt) <= CONTEXT_TTL_TICKS) {
                    chosen = s.lastAttacked;
                    chosenAt = s.lastAttackedAt;
                }
            } catch (Throwable ignored) {}
            try {
                if (s.lastAttackedBy != null && (now - s.lastAttackedByAt) <= CONTEXT_TTL_TICKS && s.lastAttackedByAt >= chosenAt) {
                    chosen = s.lastAttackedBy;
                    chosenAt = s.lastAttackedByAt;
                }
            } catch (Throwable ignored) {}

            if (chosen == null) {
                // Vanilla fallback with a time gate.
                long bestAge = Long.MAX_VALUE;
                if (hurtMob != null && hurtMob.isAlive()) {
                    long age = Math.max(0L, (long) sp.tickCount - (long) hurtMobTs);
                    if (age <= CONTEXT_TTL_TICKS) {
                        chosen = hurtMob.getUUID();
                        bestAge = age;
                    }
                }
                if (hurtBy != null && hurtBy.isAlive()) {
                    long age = Math.max(0L, (long) sp.tickCount - (long) hurtByTs);
                    if (age <= CONTEXT_TTL_TICKS && age <= bestAge) {
                        chosen = hurtBy.getUUID();
                    }
                }
            }

            if (chosen != null) {
                s.targets.add(chosen);
            } else {
                // No target: rally near the owner for a short window.
                s.rallyUntil = now + RALLY_TICKS;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HelpChatCommandService.activateFromPlayerContext failed (soft): {}", t.toString());
        }
    }

    public static void recordOwnerAttacked(ServerPlayer sp, UUID targetUuid) {
        try {
            if (sp == null || targetUuid == null) return;
            UUID owner = sp.getUUID();
            Session s = SESSIONS.computeIfAbsent(owner, k -> new Session());
            long now = 0L;
            try { now = sp.level().getGameTime(); } catch (Throwable ignored) { now = 0L; }
            s.lastAttacked = targetUuid;
            s.lastAttackedAt = now;
        } catch (Throwable ignored) {}
    }

    public static void recordOwnerAttackedBy(ServerPlayer sp, UUID attackerUuid) {
        try {
            if (sp == null || attackerUuid == null) return;
            UUID owner = sp.getUUID();
            Session s = SESSIONS.computeIfAbsent(owner, k -> new Session());
            long now = 0L;
            try { now = sp.level().getGameTime(); } catch (Throwable ignored) { now = 0L; }
            s.lastAttackedBy = attackerUuid;
            s.lastAttackedByAt = now;
        } catch (Throwable ignored) {}
    }

    public static boolean isRallyActive(UUID playerUuid, long nowGameTime) {
        try {
            Session s = playerUuid == null ? null : SESSIONS.get(playerUuid);
            if (s == null) return false;
            return s.rallyUntil > nowGameTime;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void addTarget(UUID playerUuid, UUID targetUuid) {
        try {
            if (playerUuid == null || targetUuid == null) return;
            Session s = SESSIONS.computeIfAbsent(playerUuid, k -> new Session());
            s.targets.add(targetUuid);
        } catch (Throwable ignored) {}
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (SESSIONS.isEmpty()) return;

            Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Session> en = it.next();
                UUID owner = en.getKey();
                Session s = en.getValue();
                if (owner == null || s == null) {
                    it.remove();
                    continue;
                }

                ServerPlayer sp = server.getPlayerList().getPlayer(owner);
                if (sp == null) {
                    // Keep state for offline players? For now, drop it to avoid leaks.
                    it.remove();
                    continue;
                }

                // Observe new player combat events.
                observePlayer(sp, s);

                // Prune dead/missing targets.
                prune(server, s);

                // Expire context/rally.
                long now = 0L;
                try { now = sp.level().getGameTime(); } catch (Throwable ignored) { now = 0L; }
                if (s.lastAttackedAt > 0L && (now - s.lastAttackedAt) > CONTEXT_TTL_TICKS) {
                    s.lastAttacked = null;
                    s.lastAttackedAt = 0L;
                }
                if (s.lastAttackedByAt > 0L && (now - s.lastAttackedByAt) > CONTEXT_TTL_TICKS) {
                    s.lastAttackedBy = null;
                    s.lastAttackedByAt = 0L;
                }
                if (s.rallyUntil > 0L && now >= s.rallyUntil) {
                    s.rallyUntil = 0L;
                }

                if (s.targets.isEmpty() && s.rallyUntil <= now) {
                    it.remove();
                }
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HelpChatCommandService.tick failed (soft): {}", t.toString());
        }
    }

    private static void observePlayer(ServerPlayer sp, Session s) {
        try {
            if (sp == null || s == null) return;

            int hurtByTs = 0;
            int hurtMobTs = 0;
            try { hurtByTs = sp.getLastHurtByMobTimestamp(); } catch (Throwable ignored) { hurtByTs = 0; }
            try { hurtMobTs = sp.getLastHurtMobTimestamp(); } catch (Throwable ignored) { hurtMobTs = 0; }

            if (hurtByTs > s.lastHurtByTs) {
                s.lastHurtByTs = hurtByTs;
                LivingEntity attacker = null;
                try { attacker = sp.getLastHurtByMob(); } catch (Throwable ignored) { attacker = null; }
                if (attacker != null && attacker.isAlive()) {
                    s.targets.add(attacker.getUUID());
                    try { recordOwnerAttackedBy(sp, attacker.getUUID()); } catch (Throwable ignored2) {}
                }
            }

            if (hurtMobTs > s.lastHurtMobTs) {
                s.lastHurtMobTs = hurtMobTs;
                LivingEntity victim = null;
                try { victim = sp.getLastHurtMob(); } catch (Throwable ignored) { victim = null; }
                if (victim != null && victim.isAlive()) {
                    s.targets.add(victim.getUUID());
                    try { recordOwnerAttacked(sp, victim.getUUID()); } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void prune(MinecraftServer server, Session s) {
        try {
            if (server == null || s == null) return;
            if (s.targets.isEmpty()) return;

            Iterator<UUID> it = s.targets.iterator();
            while (it.hasNext()) {
                UUID u = it.next();
                if (u == null) { it.remove(); continue; }

                Entity ent = findEntity(server, u);
                if (!(ent instanceof LivingEntity le) || !le.isAlive()) {
                    it.remove();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Entity findEntity(MinecraftServer server, UUID uuid) {
        try {
            if (server == null || uuid == null) return null;
            for (ServerLevel lvl : server.getAllLevels()) {
                try {
                    Entity e = lvl.getEntity(uuid);
                    if (e != null) return e;
                } catch (Throwable ignored) {}
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
