// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/SearchService.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.logic.TradeUtil;
import org.z2six.ezvillagerreroll.network.PacketAutoSearchDone;
import org.z2six.ezvillagerreroll.network.PacketOpenBusyScreen;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SearchService {

    private SearchService() {}

    private static final class Task {
        final UUID villagerUuid;
        final int villagerEntityId;
        final UUID ownerPlayerUuid;
        final List<ItemStack> requested;
        final Set<String> requestedKeys;

        long nextRerollGameTime;
        int cooldownTicks;

        Task(Villager vill, ServerPlayer owner, List<ItemStack> requested, long now, int cooldownTicks) {
            this(
                    vill == null ? null : vill.getUUID(),
                    vill == null ? -1 : vill.getId(),
                    owner == null ? null : owner.getUUID(),
                    requested,
                    now + Math.max(1, cooldownTicks),
                    Math.max(1, cooldownTicks)
            );
        }

        Task(UUID villagerUuid, int villagerEntityId, UUID ownerPlayerUuid, List<ItemStack> requested, long nextRerollGameTime, int cooldownTicks) {
            this.villagerUuid = villagerUuid;
            this.villagerEntityId = villagerEntityId;
            this.ownerPlayerUuid = ownerPlayerUuid;
            this.requested = requested == null ? List.of() : new ArrayList<>(requested);

            Set<String> keys = new HashSet<>();
            for (ItemStack s : this.requested) {
                if (s == null || s.isEmpty()) continue;
                keys.add(CatalogBuilder.keyOf(s));
            }
            this.requestedKeys = keys;

            this.nextRerollGameTime = nextRerollGameTime;
            this.cooldownTicks = Math.max(1, cooldownTicks);
        }
    }

    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();

    // Vanilla glow is not per-player; we approximate by enabling glow only when someone is near.
    private static final double GLOW_RANGE_BLOCKS = 6.0;
    private static final double GLOW_RANGE_SQR = GLOW_RANGE_BLOCKS * GLOW_RANGE_BLOCKS;

    public static boolean isBusy(Villager vill) {
        try {
            if (vill == null) return false;
            return TASKS.containsKey(vill.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Called from SearchSavedData on server start.
     * Imports tasks into memory so search resumes after restart/crash.
     */
    public static void importFromSavedData(MinecraftServer server, SearchSavedData data) {
        try {
            if (server == null || data == null) return;

            // Clear current tasks (server just started, but be defensive)
            TASKS.clear();

            int imported = 0;
            int skipped = 0;

            for (SearchSavedData.TaskData td : data.activeTasks().values()) {
                try {
                    if (td == null || td.villagerUuid == null || td.ownerPlayerUuid == null) {
                        skipped++;
                        continue;
                    }
                    if (td.requestedKeys == null || td.requestedKeys.isEmpty()) {
                        // backfill keys from requested stacks, if possible
                        Set<String> keys = new HashSet<>();
                        if (td.requested != null) {
                            for (ItemStack s : td.requested) {
                                if (s == null || s.isEmpty()) continue;
                                keys.add(CatalogBuilder.keyOf(s));
                            }
                        }
                        td.requestedKeys = keys;
                    }
                    if (td.requestedKeys == null || td.requestedKeys.isEmpty()) {
                        skipped++;
                        continue;
                    }

                    // Keep nextRerollGameTime as-is; but if it's clearly invalid, push it forward a bit.
                    long next = td.nextRerollGameTime;
                    int cd = Math.max(1, td.cooldownTicks);
                    if (next < 0) next = 0;

                    Task t = new Task(
                            td.villagerUuid,
                            td.villagerEntityId,
                            td.ownerPlayerUuid,
                            td.requested,
                            next,
                            cd
                    );

                    TASKS.put(td.villagerUuid, t);
                    imported++;

                } catch (Throwable each) {
                    skipped++;
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchService.importFromSavedData: imported={} skipped={} (nowTasks={})",
                    imported, skipped, TASKS.size());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.importFromSavedData failed", t);
        }
    }

    /**
     * Called from SearchSavedData on server stop.
     * Exports current tasks so search can resume after restart/crash.
     */
    public static void exportToSavedData(MinecraftServer server, SearchSavedData data) {
        try {
            if (server == null || data == null) return;

            data.activeTasks().clear();

            int exported = 0;

            for (Task t : TASKS.values()) {
                try {
                    if (t == null || t.villagerUuid == null || t.ownerPlayerUuid == null) continue;
                    if (t.requestedKeys == null || t.requestedKeys.isEmpty()) continue;

                    SearchSavedData.TaskData td = new SearchSavedData.TaskData();
                    td.villagerUuid = t.villagerUuid;
                    td.villagerEntityId = t.villagerEntityId;
                    td.ownerPlayerUuid = t.ownerPlayerUuid;
                    td.nextRerollGameTime = t.nextRerollGameTime;
                    td.cooldownTicks = Math.max(1, t.cooldownTicks);

                    td.requested = new ArrayList<>();
                    for (ItemStack s : t.requested) {
                        if (s == null || s.isEmpty()) continue;
                        td.requested.add(s.copy());
                    }

                    td.requestedKeys = new HashSet<>(t.requestedKeys);

                    // Best-effort glow state snapshot (not critical)
                    td.wasGlowingAtStart = false;
                    try {
                        Villager vill = resolveVillagerByUuid(server, t.villagerUuid);
                        if (vill != null) td.wasGlowingAtStart = vill.isCurrentlyGlowing();
                    } catch (Throwable ignored) {}

                    data.activeTasks().put(td.villagerUuid, td);
                    exported++;

                } catch (Throwable ignoredEach) {}
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchService.exportToSavedData: exported={} (activeTasksNow={})",
                    exported, data.activeTasks().size());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.exportToSavedData failed", t);
        }
    }

    public static void start(ServerPlayer sp, Villager vill, List<ItemStack> requested) {
        try {
            if (sp == null || vill == null) return;

            int cooldown = Math.max(1, ServerConfig.cooldownTicks);
            long now = vill.level().getGameTime();

            Task t = new Task(vill, sp, requested, now, cooldown);

            if (t.requestedKeys.isEmpty()) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchService.start: requested list empty -> ignoring (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            TASKS.put(vill.getUUID(), t);

            EZVillagerReroll.LOG().info("[EZVR] Auto-search START: player={} villager={} entityId={} requested={} cooldownTicks={}",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getId(),
                    t.requestedKeys.size(),
                    cooldown
            );

            // Update glow immediately (distance-gated).
            try {
                updateGlowForBusyVillager(vill, sp.server);
            } catch (Throwable ignored) {}

            // Immediate match check (fast path)
            if (containsAnyRequested(vill, t.requestedKeys)) {
                EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                        vill.getUUID(), vill.getId(), t.requestedKeys.size());
                TASKS.remove(vill.getUUID());

                // Ensure glow clears when done.
                trySetVillagerGlow(vill, false);

                notifyOwnerDone(sp.server, t);
            }

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.start failed", e);
        }
    }

    public static void cancelByEntityId(ServerPlayer sp, int villagerEntityId) {
        try {
            if (sp == null) return;

            Villager vill = resolveVillagerByEntityId(sp.serverLevel(), villagerEntityId);
            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] cancelByEntityId: villager not found (entityId={}, player={})",
                        villagerEntityId, sp.getGameProfile().getName());
                return;
            }

            Task removed = TASKS.remove(vill.getUUID());
            if (removed != null) {
                EZVillagerReroll.LOG().info("[EZVR] Auto-search CANCEL: player={} villager={} entityId={}",
                        sp.getGameProfile().getName(), vill.getUUID(), vill.getId());
            }

            // Ensure glow clears when cancelled.
            trySetVillagerGlow(vill, false);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] cancelByEntityId failed", t);
        }
    }

    public static void openBusyScreen(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null) return;

            Task t = TASKS.get(vill.getUUID());
            if (t == null) {
                EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: villager not busy (player={} villager={} entityId={})",
                        sp.getGameProfile().getName(), vill.getUUID(), vill.getId());
                return;
            }

            try {
                sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new PacketOpenBusyScreen(vill.getId(), t.requested)
                ));
                EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: sent PacketOpenBusyScreen (player={} villagerEntityId={} req={})",
                        sp.getGameProfile().getName(), vill.getId(), t.requestedKeys.size());
            } catch (Throwable sendErr) {
                EZVillagerReroll.LOG().error("[EZVR] Failed to send Busy screen packet (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID(), sendErr);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] openBusyScreen failed", t);
        }
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (TASKS.isEmpty()) return;

            Iterator<Map.Entry<UUID, Task>> it = TASKS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Task> en = it.next();
                Task task = en.getValue();
                if (task == null) {
                    it.remove();
                    continue;
                }

                Villager vill = resolveVillagerByUuid(server, task.villagerUuid);
                if (vill == null) continue;

                // Update distance-gated glow each tick for this busy villager.
                try {
                    updateGlowForBusyVillager(vill, server);
                } catch (Throwable ignored) {}

                long now = vill.level().getGameTime();
                if (now < task.nextRerollGameTime) continue;

                int cooldown = Math.max(1, ServerConfig.cooldownTicks);
                task.cooldownTicks = cooldown;
                task.nextRerollGameTime = now + cooldown;

                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    notifyOwnerDone(server, task);
                    continue;
                }

                try {
                    TradeUtil.rebuildOffersInternal(vill, null, false);
                } catch (Throwable rerollErr) {
                    EZVillagerReroll.LOG().error("[EZVR] Auto-search reroll failed (villager={} entityId={})",
                            vill.getUUID(), vill.getId(), rerollErr);
                    continue;
                }

                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search FOUND match: villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    notifyOwnerDone(server, task);
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.tick failed", t);
        }
    }

    private static void notifyOwnerDone(MinecraftServer server, Task task) {
        try {
            if (server == null || task == null) return;

            ServerPlayer owner = server.getPlayerList().getPlayer(task.ownerPlayerUuid);
            if (owner == null) {
                EZVillagerReroll.LOG().debug("[EZVR] notifyOwnerDone: owner offline (playerUuid={})", task.ownerPlayerUuid);
                // If you want offline notification delivery, this is where we’d enqueue into SearchSavedData.pendingDoneByOwner.
                return;
            }

            try {
                owner.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new PacketAutoSearchDone(task.villagerEntityId)
                ));
                EZVillagerReroll.LOG().debug("[EZVR] notifyOwnerDone: sent PacketAutoSearchDone(villagerEntityId={}) to {}",
                        task.villagerEntityId, owner.getGameProfile().getName());
            } catch (Throwable sendErr) {
                EZVillagerReroll.LOG().warn("[EZVR] notifyOwnerDone: send failed (soft): {}", sendErr.toString());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] notifyOwnerDone failed", t);
        }
    }

    private static boolean containsAnyRequested(Villager vill, Set<String> requestedKeys) {
        try {
            if (vill == null) return false;
            if (requestedKeys == null || requestedKeys.isEmpty()) return false;

            MerchantOffers offers = vill.getOffers();
            if (offers == null || offers.isEmpty()) return false;

            for (MerchantOffer o : offers) {
                if (o == null) continue;
                ItemStack out = o.getResult();
                if (out == null || out.isEmpty()) continue;

                String k = CatalogBuilder.keyOf(out);
                if (requestedKeys.contains(k)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Villager resolveVillagerByEntityId(ServerLevel lvl, int entityId) {
        try {
            if (lvl == null) return null;
            if (entityId < 0) return null;
            Entity e = lvl.getEntity(entityId);
            return (e instanceof Villager v) ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Villager resolveVillagerByUuid(MinecraftServer server, UUID uuid) {
        try {
            if (server == null || uuid == null) return null;

            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(uuid);
                if (e instanceof Villager v) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void updateGlowForBusyVillager(Villager vill, MinecraftServer server) {
        try {
            if (vill == null || server == null) return;
            if (!(vill.level() instanceof ServerLevel sl)) return;

            boolean anyNear = false;
            try {
                List<ServerPlayer> players = sl.players();
                for (ServerPlayer sp : players) {
                    if (sp == null) continue;
                    if (sp.isSpectator()) continue;
                    double d2 = sp.distanceToSqr(vill);
                    if (d2 <= GLOW_RANGE_SQR) {
                        anyNear = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                anyNear = false;
            }

            trySetVillagerGlow(vill, anyNear);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] updateGlowForBusyVillager failed (soft): {}", t.toString());
        }
    }

    private static void trySetVillagerGlow(Villager vill, boolean glow) {
        try {
            if (vill == null) return;

            boolean prev;
            try {
                prev = vill.isCurrentlyGlowing();
            } catch (Throwable ignored) {
                prev = !glow;
            }

            if (prev == glow) return;

            vill.setGlowingTag(glow);

            EZVillagerReroll.LOG().debug("[EZVR] Villager glow updated: villager={} entityId={} glow={}",
                    vill.getUUID(), vill.getId(), glow);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] trySetVillagerGlow failed (soft): {}", t.toString());
        }
    }
}
