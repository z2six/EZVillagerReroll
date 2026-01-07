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
import org.z2six.ezvillagerreroll.network.PacketOpenBusyScreen;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side auto-search state + tick loop.
 *
 * This exists primarily to fix compilation and to provide a real place for your feature logic.
 * It is intentionally conservative: it never crashes the server; it logs and skips.
 */
public final class SearchService {

    private SearchService() {}

    private static final class Task {
        final UUID villagerUuid;
        final int villagerEntityId;
        final UUID ownerPlayerUuid;
        final List<ItemStack> requested;
        final Set<String> requestedKeys;

        long nextRerollGameTime;

        Task(Villager vill, ServerPlayer owner, List<ItemStack> requested, long now, int cooldownTicks) {
            this.villagerUuid = vill.getUUID();
            this.villagerEntityId = vill.getId();
            this.ownerPlayerUuid = owner.getUUID();
            this.requested = requested == null ? List.of() : new ArrayList<>(requested);

            Set<String> keys = new HashSet<>();
            for (ItemStack s : this.requested) {
                if (s == null || s.isEmpty()) continue;
                keys.add(CatalogBuilder.keyOf(s));
            }
            this.requestedKeys = keys;

            int cd = Math.max(1, cooldownTicks);
            this.nextRerollGameTime = now + cd;
        }
    }

    // villagerUuid -> task
    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();

    public static boolean isBusy(Villager vill) {
        try {
            if (vill == null) return false;
            return TASKS.containsKey(vill.getUUID());
        } catch (Throwable t) {
            return false;
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

            // Immediately close menu client-side is already done by the client screen.
            // If a player tries to interact, BusyVillagerBlocker will open Busy screen.

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
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] cancelByEntityId failed", t);
        }
    }

    public static void openBusyScreen(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null) return;

            Task t = TASKS.get(vill.getUUID());
            if (t == null) return;

            // Send busy screen packet to client.
            // You already have a safe "send custom payload" path elsewhere; here we use ctx.reply in handlers,
            // but in event code we must send manually. The simplest is using ServerPlayer#connection.
            try {
                sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new PacketOpenBusyScreen(vill.getId(), t.requested)
                ));
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

            // Iterate tasks; resolve villager by UUID each time (safe across chunk unloads).
            Iterator<Map.Entry<UUID, Task>> it = TASKS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Task> en = it.next();
                Task task = en.getValue();
                if (task == null) {
                    it.remove();
                    continue;
                }

                Villager vill = resolveVillagerByUuid(server, task.villagerUuid);
                if (vill == null) {
                    // Villager not loaded; keep task but don't spam.
                    continue;
                }

                long now = vill.level().getGameTime();
                if (now < task.nextRerollGameTime) continue;

                int cooldown = Math.max(1, ServerConfig.cooldownTicks);
                task.nextRerollGameTime = now + cooldown;

                // If already contains requested, finish immediately.
                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();
                    continue;
                }

                // Perform one reroll pass (must preserve locked trades; TradeUtil already handles that).
                // We do NOT sync GUI here; no player GUI should be open during busy state.
                try {
                    TradeUtil.rebuildOffersInternal(vill, null, false);
                } catch (Throwable rerollErr) {
                    EZVillagerReroll.LOG().error("[EZVR] Auto-search reroll failed (villager={} entityId={})",
                            vill.getUUID(), vill.getId(), rerollErr);
                    continue;
                }

                // Check for requested outputs after reroll.
                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search FOUND match: villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.tick failed", t);
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

            // Search loaded levels only (safe).
            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(uuid);
                if (e instanceof Villager v) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
