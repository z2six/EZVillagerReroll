// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/SearchService.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
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
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.logic.TradeUtil;
import org.z2six.ezvillagerreroll.network.PacketAutoSearchDone;
import org.z2six.ezvillagerreroll.network.PacketOpenAutoSearchPaymentScreen;
import org.z2six.ezvillagerreroll.network.PacketOpenBusyScreen;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SearchService {

    private SearchService() {}

    private static final int AUTO_HOURLY_BASE_MULTIPLIER = 10;

    private static final class Task {
        final UUID villagerUuid;
        final int villagerEntityId;
        final UUID ownerPlayerUuid;
        final List<ItemStack> requested;
        final Set<String> requestedKeys;

        // snapshot at START (for decline UI + lock highlights)
        final ListTag offersBeforeTag;
        final long lockMaskBefore;

        // derived-at-start (for XP computation)
        final int offersAtStart;
        final int lockedAtStart;
        final int offersRerolledPerRerollAtStart;

        // NEW: number of successful rerolls performed during this task
        int rerollCount = 0;

        long startedAtGameTime;
        long nextRerollGameTime;
        int cooldownTicks;

        Task(Villager vill, ServerPlayer owner, List<ItemStack> requested, long now, int cooldownTicks) {
            this(
                    vill == null ? null : vill.getUUID(),
                    vill == null ? -1 : vill.getId(),
                    owner == null ? null : owner.getUUID(),
                    requested,
                    now,
                    now + Math.max(1, cooldownTicks),
                    Math.max(1, cooldownTicks),
                    snapshotOffersCodecSafe(vill),
                    snapshotLockMaskSafe(vill),
                    0
            );
        }

        Task(UUID villagerUuid,
             int villagerEntityId,
             UUID ownerPlayerUuid,
             List<ItemStack> requested,
             long startedAtGameTime,
             long nextRerollGameTime,
             int cooldownTicks,
             ListTag offersBeforeTag,
             long lockMaskBefore,
             int rerollCount
        ) {
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

            this.startedAtGameTime = Math.max(0L, startedAtGameTime);
            this.nextRerollGameTime = nextRerollGameTime;
            this.cooldownTicks = Math.max(1, cooldownTicks);

            this.offersBeforeTag = offersBeforeTag == null ? new ListTag() : offersBeforeTag;

            int startOffers = 0;
            try { startOffers = Math.max(0, this.offersBeforeTag.size()); } catch (Throwable ignored) { startOffers = 0; }
            this.offersAtStart = startOffers;

            long sanitizedMask = sanitizeMaskForSize(lockMaskBefore, this.offersAtStart);
            this.lockMaskBefore = sanitizedMask;

            int locked = 0;
            try { locked = Long.bitCount(sanitizedMask); } catch (Throwable ignored) { locked = 0; }
            this.lockedAtStart = locked;

            int rerolledPer = Math.max(0, this.offersAtStart - this.lockedAtStart);
            if (rerolledPer > 64) rerolledPer = 64; // sanity cap
            this.offersRerolledPerRerollAtStart = rerolledPer;

            this.rerollCount = Math.max(0, rerollCount);
        }
    }

    public static final class Settlement {
        final UUID villagerUuid;
        final int villagerEntityId;
        final UUID ownerPlayerUuid;

        final long startedAtGameTime;
        final long completedAtGameTime;

        final int hourlyCost;
        final int finalCost;

        // UI snapshots
        final ListTag offersIfPayTag;
        final ListTag offersIfDeclineTag;

        // lock state at START (green outlines)
        final long lockMaskBefore;

        // targets (yellow matching)
        final List<String> requestedTargets;

        // NEW: villager XP to award if paid
        final int totalVillagerXp;

        Settlement(UUID villagerUuid,
                   int villagerEntityId,
                   UUID ownerPlayerUuid,
                   long startedAtGameTime,
                   long completedAtGameTime,
                   int hourlyCost,
                   int finalCost,
                   ListTag offersIfPayTag,
                   ListTag offersIfDeclineTag,
                   long lockMaskBefore,
                   List<String> requestedTargets,
                   int totalVillagerXp
        ) {
            this.villagerUuid = villagerUuid;
            this.villagerEntityId = villagerEntityId;
            this.ownerPlayerUuid = ownerPlayerUuid;
            this.startedAtGameTime = startedAtGameTime;
            this.completedAtGameTime = completedAtGameTime;
            this.hourlyCost = Math.max(0, hourlyCost);
            this.finalCost = Math.max(0, finalCost);

            this.offersIfPayTag = offersIfPayTag == null ? new ListTag() : offersIfPayTag;
            this.offersIfDeclineTag = offersIfDeclineTag == null ? new ListTag() : offersIfDeclineTag;

            this.lockMaskBefore = lockMaskBefore;

            if (requestedTargets == null) {
                this.requestedTargets = List.of();
            } else {
                ArrayList<String> copy = new ArrayList<>(Math.min(256, requestedTargets.size()));
                for (int i = 0; i < requestedTargets.size() && i < 256; i++) {
                    String s = requestedTargets.get(i);
                    if (s == null) continue;
                    s = s.trim();
                    if (s.isEmpty()) continue;
                    copy.add(s);
                }
                this.requestedTargets = copy;
            }

            this.totalVillagerXp = Math.max(0, totalVillagerXp);
        }
    }

    private static final Map<UUID, Task> TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Settlement> SETTLEMENTS = new ConcurrentHashMap<>();

    private static final double GLOW_RANGE_BLOCKS = 6.0;
    private static final double GLOW_RANGE_SQR = GLOW_RANGE_BLOCKS * GLOW_RANGE_BLOCKS;

    public static boolean isBusy(Villager vill) {
        try {
            if (vill == null) return false;
            UUID id = vill.getUUID();
            return TASKS.containsKey(id) || SETTLEMENTS.containsKey(id);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isAwaitingPayment(Villager vill) {
        try {
            if (vill == null) return false;
            return SETTLEMENTS.containsKey(vill.getUUID());
        } catch (Throwable t) {
            return false;
        }
    }

    public static Settlement getSettlement(Villager vill) {
        try {
            if (vill == null) return null;
            return SETTLEMENTS.get(vill.getUUID());
        } catch (Throwable t) {
            return null;
        }
    }

    public static void importFromSavedData(MinecraftServer server, SearchSavedData data) {
        try {
            if (server == null || data == null) return;

            TASKS.clear();
            SETTLEMENTS.clear();

            int imported = 0;
            int skipped = 0;

            for (SearchSavedData.TaskData td : data.activeTasks().values()) {
                try {
                    if (td == null || td.villagerUuid == null || td.ownerPlayerUuid == null) {
                        skipped++;
                        continue;
                    }

                    if (td.requestedKeys == null || td.requestedKeys.isEmpty()) {
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

                    long next = td.nextRerollGameTime;
                    int cd = Math.max(1, td.cooldownTicks);
                    if (next < 0) next = 0;

                    long started = Math.max(0L, next - cd);

                    ListTag before = td.offersBeforeTag == null ? new ListTag() : td.offersBeforeTag;
                    long lockMaskBefore = td.lockMaskBefore;

                    int rr = Math.max(0, td.rerollCount);

                    Task t = new Task(
                            td.villagerUuid,
                            td.villagerEntityId,
                            td.ownerPlayerUuid,
                            td.requested,
                            started,
                            next,
                            cd,
                            before,
                            lockMaskBefore,
                            rr
                    );

                    TASKS.put(td.villagerUuid, t);
                    imported++;

                } catch (Throwable each) {
                    skipped++;
                }
            }

            int settleImported = 0;
            int settleSkipped = 0;

            for (SearchSavedData.SettlementData sd : data.settlements().values()) {
                try {
                    if (sd == null || sd.villagerUuid == null) {
                        settleSkipped++;
                        continue;
                    }

                    Settlement s = new Settlement(
                            sd.villagerUuid,
                            sd.villagerEntityId,
                            sd.ownerPlayerUuid,
                            Math.max(0L, sd.startedAtGameTime),
                            Math.max(0L, sd.completedAtGameTime),
                            Math.max(0, sd.hourlyCost),
                            Math.max(0, sd.finalCost),
                            sd.offersIfPay == null ? new ListTag() : sd.offersIfPay,
                            sd.offersIfDecline == null ? new ListTag() : sd.offersIfDecline,
                            sd.lockMaskBefore,
                            sd.requestedTargets == null ? List.of() : sd.requestedTargets,
                            Math.max(0, sd.totalVillagerXp)
                    );

                    SETTLEMENTS.put(sd.villagerUuid, s);
                    settleImported++;
                } catch (Throwable ignored) {
                    settleSkipped++;
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchService.importFromSavedData: importedTasks={} skippedTasks={} importedSettlements={} skippedSettlements={}",
                    imported, skipped, settleImported, settleSkipped);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.importFromSavedData failed", t);
        }
    }

    public static void exportToSavedData(MinecraftServer server, SearchSavedData data) {
        try {
            if (server == null || data == null) return;

            data.activeTasks().clear();
            data.settlements().clear();

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

                    td.offersBeforeTag = deepCopyOfferList(t.offersBeforeTag);
                    td.lockMaskBefore = t.lockMaskBefore;

                    td.rerollCount = Math.max(0, t.rerollCount);

                    td.wasGlowingAtStart = false;
                    try {
                        Villager vill = resolveVillagerByUuid(server, t.villagerUuid);
                        if (vill != null) td.wasGlowingAtStart = vill.isCurrentlyGlowing();
                    } catch (Throwable ignored) {}

                    data.activeTasks().put(td.villagerUuid, td);
                    exported++;

                } catch (Throwable ignoredEach) {}
            }

            int settleExported = 0;

            for (Settlement s : SETTLEMENTS.values()) {
                try {
                    if (s == null || s.villagerUuid == null) continue;

                    SearchSavedData.SettlementData sd = new SearchSavedData.SettlementData();
                    sd.villagerUuid = s.villagerUuid;
                    sd.villagerEntityId = s.villagerEntityId;
                    sd.ownerPlayerUuid = s.ownerPlayerUuid;

                    sd.startedAtGameTime = s.startedAtGameTime;
                    sd.completedAtGameTime = s.completedAtGameTime;

                    sd.hourlyCost = Math.max(0, s.hourlyCost);
                    sd.finalCost = Math.max(0, s.finalCost);

                    sd.offersIfPay = deepCopyOfferList(s.offersIfPayTag);
                    sd.offersIfDecline = deepCopyOfferList(s.offersIfDeclineTag);
                    sd.lockMaskBefore = s.lockMaskBefore;

                    sd.requestedTargets = new ArrayList<>();
                    if (s.requestedTargets != null) {
                        for (int i = 0; i < s.requestedTargets.size() && i < 256; i++) {
                            String r = s.requestedTargets.get(i);
                            if (r == null) continue;
                            r = r.trim();
                            if (r.isEmpty()) continue;
                            sd.requestedTargets.add(r);
                        }
                    }

                    sd.totalVillagerXp = Math.max(0, s.totalVillagerXp);

                    data.settlements().put(sd.villagerUuid, sd);
                    settleExported++;
                } catch (Throwable ignored) {}
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchService.exportToSavedData: exportedTasks={} exportedSettlements={}", exported, settleExported);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.exportToSavedData failed", t);
        }
    }

    public static void start(ServerPlayer sp, Villager vill, List<ItemStack> requested) {
        try {
            if (sp == null || vill == null) return;

            if (SETTLEMENTS.containsKey(vill.getUUID())) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchService.start refused: settlement pending (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            int cooldown = Math.max(1, ServerConfig.cooldownTicks);
            long now = vill.level().getGameTime();

            Task t = new Task(vill, sp, requested, now, cooldown);

            if (t.requestedKeys.isEmpty()) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchService.start: requested list empty -> ignoring (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            TASKS.put(vill.getUUID(), t);

            EZVillagerReroll.LOG().info("[EZVR] Auto-search START: player={} villager={} entityId={} requested={} cooldownTicks={} lockMaskBefore={} offersBefore={} offersAtStart={} lockedAtStart={} offersRerolledPerRerollAtStart={}",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getId(),
                    t.requestedKeys.size(),
                    cooldown,
                    Long.toUnsignedString(t.lockMaskBefore),
                    t.offersBeforeTag == null ? -1 : t.offersBeforeTag.size(),
                    t.offersAtStart,
                    t.lockedAtStart,
                    t.offersRerolledPerRerollAtStart
            );

            try { updateGlowForBusyVillager(vill, sp.server); } catch (Throwable ignored) {}

            if (containsAnyRequested(vill, t.requestedKeys)) {
                EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                        vill.getUUID(), vill.getId(), t.requestedKeys.size());
                TASKS.remove(vill.getUUID());

                trySetVillagerGlow(vill, false);
                createSettlementAndNotify(serverOf(sp), vill, t, now);
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
                EZVillagerReroll.LOG().info("[EZVR] Auto-search CANCEL: player={} villager={} entityId={} rerollCount={}",
                        sp.getGameProfile().getName(), vill.getUUID(), vill.getId(), removed.rerollCount);
            }

            trySetVillagerGlow(vill, false);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] cancelByEntityId failed", t);
        }
    }

    public static void openBusyScreen(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null) return;

            Settlement settle = SETTLEMENTS.get(vill.getUUID());
            if (settle != null) {
                int elapsedTicks = (int) Math.max(0L, settle.completedAtGameTime - settle.startedAtGameTime);

                try {
                    sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                            new PacketOpenAutoSearchPaymentScreen(
                                    vill.getId(),
                                    settle.hourlyCost,
                                    settle.finalCost,
                                    elapsedTicks,
                                    settle.offersIfPayTag,
                                    settle.offersIfDeclineTag,
                                    settle.lockMaskBefore,
                                    settle.requestedTargets,
                                    settle.totalVillagerXp
                            )
                    ));

                    EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: sent PacketOpenAutoSearchPaymentScreen (player={} villagerEntityId={} hourly={} final={} elapsedTicks={} totalVillagerXp={} payOffers={} declineOffers={} lockMaskBefore={} requestedTargets={})",
                            sp.getGameProfile().getName(),
                            vill.getId(),
                            settle.hourlyCost,
                            settle.finalCost,
                            elapsedTicks,
                            settle.totalVillagerXp,
                            settle.offersIfPayTag == null ? -1 : settle.offersIfPayTag.size(),
                            settle.offersIfDeclineTag == null ? -1 : settle.offersIfDeclineTag.size(),
                            Long.toUnsignedString(settle.lockMaskBefore),
                            settle.requestedTargets == null ? -1 : settle.requestedTargets.size()
                    );

                } catch (Throwable sendErr) {
                    EZVillagerReroll.LOG().error("[EZVR] Failed to send payment screen packet (player={} villager={})",
                            sp.getGameProfile().getName(), vill.getUUID(), sendErr);
                }
                return;
            }

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
                EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: sent PacketOpenBusyScreen (player={} villagerEntityId={} req={} rerollCount={})",
                        sp.getGameProfile().getName(), vill.getId(), t.requestedKeys.size(), t.rerollCount);
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

                if (SETTLEMENTS.containsKey(vill.getUUID())) {
                    EZVillagerReroll.LOG().warn("[EZVR] SearchService.tick: task exists but settlement pending; removing task (villager={})", vill.getUUID());
                    it.remove();
                    continue;
                }

                try { updateGlowForBusyVillager(vill, server); } catch (Throwable ignored) {}

                long now = vill.level().getGameTime();
                if (now < task.nextRerollGameTime) continue;

                int cooldown = Math.max(1, ServerConfig.cooldownTicks);
                task.cooldownTicks = cooldown;
                task.nextRerollGameTime = now + cooldown;

                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={} rerollCount={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size(), task.rerollCount);
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    createSettlementAndNotify(server, vill, task, now);
                    continue;
                }

                try {
                    TradeUtil.rebuildOffersInternal(vill, null, false);
                    task.rerollCount = Math.max(0, task.rerollCount + 1);
                    EZVillagerReroll.LOG().debug("[EZVR] Auto-search reroll success: villager={} entityId={} rerollCount={}",
                            vill.getUUID(), vill.getId(), task.rerollCount);
                } catch (Throwable rerollErr) {
                    EZVillagerReroll.LOG().error("[EZVR] Auto-search reroll failed (villager={} entityId={})",
                            vill.getUUID(), vill.getId(), rerollErr);
                    continue;
                }

                if (containsAnyRequested(vill, task.requestedKeys)) {
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search FOUND match: villager={} entityId={} requestedKeys={} rerollCount={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size(), task.rerollCount);
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    createSettlementAndNotify(server, vill, task, now);
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchService.tick failed", t);
        }
    }

    private static void createSettlementAndNotify(MinecraftServer server, Villager vill, Task task, long completedAtGameTime) {
        try {
            if (server == null || vill == null || task == null) return;

            long started = Math.max(0L, task.startedAtGameTime);
            long completed = Math.max(started, completedAtGameTime);

            int hourly = computeHourlyCostServer(vill);
            int elapsedTicks = (int) Math.max(0L, completed - started);
            int finalCost = computeFinalCost(hourly, elapsedTicks);

            // Snapshot offers to show in UI
            ListTag payOffers = serializeOffersCodec(vill);
            ListTag declineOffers = deepCopyOfferList(task.offersBeforeTag);

            // requested targets = requestedKeys, but as a stable list
            List<String> requested = new ArrayList<>();
            try {
                if (task.requestedKeys != null) {
                    int n = 0;
                    for (String s : task.requestedKeys) {
                        if (s == null) continue;
                        s = s.trim();
                        if (s.isEmpty()) continue;
                        requested.add(s);
                        n++;
                        if (n >= 256) break;
                    }
                }
            } catch (Throwable ignored) {}

            int totalXp = computeTotalVillagerXpForTask(task);

            Settlement settle = new Settlement(
                    vill.getUUID(),
                    vill.getId(),
                    task.ownerPlayerUuid,
                    started,
                    completed,
                    hourly,
                    finalCost,
                    payOffers,
                    declineOffers,
                    task.lockMaskBefore,
                    requested,
                    totalXp
            );

            SETTLEMENTS.put(vill.getUUID(), settle);

            EZVillagerReroll.LOG().info("[EZVR] Auto-search SETTLEMENT created: villager={} entityId={} hourly={} elapsedTicks={} finalCost={} owner={} payOffers={} declineOffers={} lockMaskBefore={} requestedTargets={} rerollCount={} offersAtStart={} lockedAtStart={} offersRerolledPerRerollAtStart={} totalVillagerXp={}",
                    vill.getUUID(), vill.getId(), hourly, elapsedTicks, finalCost, String.valueOf(task.ownerPlayerUuid),
                    payOffers == null ? -1 : payOffers.size(),
                    declineOffers == null ? -1 : declineOffers.size(),
                    Long.toUnsignedString(task.lockMaskBefore),
                    requested.size(),
                    task.rerollCount,
                    task.offersAtStart,
                    task.lockedAtStart,
                    task.offersRerolledPerRerollAtStart,
                    totalXp
            );

            notifyOwnerDone(server, task);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] createSettlementAndNotify failed", t);
        }
    }

    private static int computeTotalVillagerXpForTask(Task task) {
        try {
            if (task == null) return 0;

            int perOffer = Math.max(0, ServerConfig.autoSearchXpPerOffer);
            if (perOffer <= 0) return 0;

            int rerolls = Math.max(0, task.rerollCount);
            int offersRerolledPer = Math.max(0, task.offersRerolledPerRerollAtStart);
            if (rerolls <= 0 || offersRerolledPer <= 0) return 0;

            long raw = (long) rerolls * (long) offersRerolledPer * (long) perOffer;
            if (raw < 0L) raw = 0L;
            if (raw > Integer.MAX_VALUE) raw = Integer.MAX_VALUE;

            return (int) raw;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int computeFinalCost(int hourlyCost, int elapsedTicks) {
        try {
            if (hourlyCost <= 0) return 0;
            if (elapsedTicks <= 0) return 0;

            double hours = elapsedTicks / 72000.0;
            double raw = hourlyCost * hours;

            long ceil = (long) Math.ceil(raw);

            if (ceil < 0L) ceil = 0L;
            if (ceil > Integer.MAX_VALUE) ceil = Integer.MAX_VALUE;
            return (int) ceil;
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    public static int computeHourlyCostServer(Villager vill) {
        try {
            if (vill == null) return 0;

            int totalOffers = (vill.getOffers() == null) ? 0 : Math.max(0, vill.getOffers().size());

            long lockMask = TradeLockState.getMask(vill);
            long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, totalOffers);
            if (sanitized != lockMask) {
                TradeLockState.setMask(vill, sanitized);
                lockMask = sanitized;
            }

            int lockedOffers = Long.bitCount(lockMask);
            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedOffers, maxDeduct);

            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);

            int effectiveOffers = Math.max(0, totalOffers - deductibleLocks);
            int paidOffers = Math.max(0, effectiveOffers - freeOffers);

            long manual = (long) paidOffers * (long) costPerOffer;
            if (manual < 0L) manual = 0L;
            if (manual > Integer.MAX_VALUE) manual = Integer.MAX_VALUE;

            long hourlyBase = manual * (long) AUTO_HOURLY_BASE_MULTIPLIER;
            if (hourlyBase < 0L) hourlyBase = 0L;
            if (hourlyBase > Integer.MAX_VALUE) hourlyBase = Integer.MAX_VALUE;

            int threshold = Math.max(0, ServerConfig.autoHourlyThreshold);
            double pct = Math.max(0.0, ServerConfig.autoHourlyDiscountOrIncreasePct);

            int steps = paidOffers - threshold;

            double factor = 1.0 - (steps * (pct / 100.0));
            if (factor < 0.0) factor = 0.0;

            long scaled = (long) Math.ceil(hourlyBase * factor);

            if (scaled < 0L) scaled = 0L;
            if (scaled > Integer.MAX_VALUE) scaled = Integer.MAX_VALUE;

            if (EZVillagerReroll.LOG().isDebugEnabled()) {
                EZVillagerReroll.LOG().debug("[EZVR] computeHourlyCostServer: offers={} locked={} deductibleLocks={} free={} paid={} manual={} hourlyBase={} threshold={} pct={} steps={} factor={} hourly={}",
                        totalOffers, lockedOffers, deductibleLocks, freeOffers, paidOffers, manual, hourlyBase,
                        threshold, pct, steps, factor, scaled);
            }

            return (int) scaled;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] computeHourlyCostServer failed (soft): {}", t.toString());
            return 0;
        }
    }

    private static void notifyOwnerDone(MinecraftServer server, Task task) {
        try {
            if (server == null || task == null) return;

            ServerPlayer owner = server.getPlayerList().getPlayer(task.ownerPlayerUuid);
            if (owner == null) {
                EZVillagerReroll.LOG().debug("[EZVR] notifyOwnerDone: owner offline (playerUuid={})", task.ownerPlayerUuid);
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

    private static MinecraftServer serverOf(ServerPlayer sp) {
        try {
            return sp == null ? null : sp.server;
        } catch (Throwable t) {
            return null;
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

    // -----------------------------------------------------------------------------------------
    // Offer serialization for settlement caching (registry-aware, wrapper format: { "v": tag })
    // -----------------------------------------------------------------------------------------

    private static final String TAG_WRAP_VALUE = "v";

    private static ListTag serializeOffersCodec(Villager vill) {
        try {
            if (vill == null) return new ListTag();
            if (!(vill.level() instanceof ServerLevel level)) return new ListTag();

            MerchantOffers offers = vill.getOffers();
            if (offers == null) return new ListTag();

            var ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            ListTag list = new ListTag();
            for (int i = 0; i < offers.size(); i++) {
                final int idx = i;
                MerchantOffer offer;
                try {
                    offer = offers.get(i);
                } catch (Throwable t) {
                    continue;
                }
                if (offer == null) continue;

                try {
                    var res = MerchantOffer.CODEC.encodeStart(ops, offer);
                    res.resultOrPartial(msg ->
                                    EZVillagerReroll.LOG().warn("[EZVR] Settlement offer encode failed (idx={}): {}", idx, msg))
                            .ifPresent(tag -> {
                                try {
                                    CompoundTag wrap = new CompoundTag();
                                    wrap.put(TAG_WRAP_VALUE, tag);
                                    list.add(wrap);
                                } catch (Throwable ignored) {}
                            });
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().warn("[EZVR] Settlement offer encode threw (idx={}): {}", idx, t.toString());
                }
            }

            return list;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] serializeOffersCodec failed (soft)", t);
            return new ListTag();
        }
    }

    private static ListTag snapshotOffersCodecSafe(Villager vill) {
        try {
            return serializeOffersCodec(vill);
        } catch (Throwable t) {
            return new ListTag();
        }
    }

    private static long snapshotLockMaskSafe(Villager vill) {
        try {
            if (vill == null) return 0L;
            int offerCount = vill.getOffers() == null ? 0 : Math.max(0, vill.getOffers().size());
            long m = TradeLockState.getMask(vill);
            return TradeLockState.sanitizeMaskForSize(m, offerCount);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static ListTag deepCopyOfferList(ListTag src) {
        try {
            ListTag out = new ListTag();
            if (src == null) return out;
            int n = Math.min(256, src.size());
            for (int i = 0; i < n; i++) {
                try {
                    CompoundTag wrap = src.getCompound(i);
                    if (wrap != null) out.add(wrap.copy());
                } catch (Throwable ignored) {}
            }
            return out;
        } catch (Throwable t) {
            return new ListTag();
        }
    }

    // ---------------------------------------------------------------------
    // Settlement accessors
    // ---------------------------------------------------------------------

    public static int getSettlementFinalCost(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            return s == null ? 0 : Math.max(0, s.finalCost);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getSettlementHourlyCost(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            return s == null ? 0 : Math.max(0, s.hourlyCost);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getSettlementElapsedTicks(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            if (s == null) return 0;
            return (int) Math.max(0L, s.completedAtGameTime - s.startedAtGameTime);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getSettlementTotalVillagerXp(Villager vill) {
        try {
            Settlement s = SETTLEMENTS.get(vill.getUUID());
            return s == null ? 0 : Math.max(0, s.totalVillagerXp);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static Settlement popSettlement(UUID villagerUuid) {
        try {
            if (villagerUuid == null) return null;
            return SETTLEMENTS.remove(villagerUuid);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // XP award helper (call this ONLY after payment has succeeded)
    // ---------------------------------------------------------------------

    public static int awardSettlementVillagerXpIfAny(Villager vill, Settlement settlement) {
        try {
            if (vill == null || settlement == null) return 0;

            int xp = Math.max(0, settlement.totalVillagerXp);
            if (xp <= 0) {
                EZVillagerReroll.LOG().debug("[EZVR] awardSettlementVillagerXpIfAny: nothing to award (xp<=0) villager={}", vill.getUUID());
                return 0;
            }

            int lvlBefore = 0;
            int xpBefore = 0;
            try { lvlBefore = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            try { xpBefore = vill.getVillagerXp(); } catch (Throwable ignored) {}

            boolean ok = addVillagerXpSafe(vill, xp);

            // IMPORTANT: now nudge vanilla to perform its own level-up process if XP threshold was reached
            boolean leveled = false;
            if (ok) {
                leveled = triggerVanillaLevelUpsIfNeeded(vill);
            }

            int lvlAfter = lvlBefore;
            int xpAfter = xpBefore;
            try { lvlAfter = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            try { xpAfter = vill.getVillagerXp(); } catch (Throwable ignored) {}

            EZVillagerReroll.LOG().info(
                    "[EZVR] awardSettlementVillagerXpIfAny: villager={} entityId={} addXp={} success={} leveledUp={} level {}->{} xp {}->{}",
                    vill.getUUID(), vill.getId(), xp, ok, leveled, lvlBefore, lvlAfter, xpBefore, xpAfter
            );

            return ok ? xp : 0;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] awardSettlementVillagerXpIfAny failed", t);
            return 0;
        }
    }

    // ---------------------------------------------------------------------
// Vanilla level-up nudging (after XP is granted)
// ---------------------------------------------------------------------

    private static boolean triggerVanillaLevelUpsIfNeeded(Villager vill) {
        try {
            if (vill == null) return false;

            boolean anyLevelChange = false;

            // Hard safety to avoid infinite loops if a mapping changes unexpectedly
            for (int guard = 0; guard < 8; guard++) {
                int level;
                int xp;
                try { level = vill.getVillagerData().getLevel(); } catch (Throwable t) { return anyLevelChange; }
                try { xp = vill.getVillagerXp(); } catch (Throwable t) { return anyLevelChange; }

                if (level >= 5) return anyLevelChange;

                int needed = getVanillaMaxXpForLevel(level);
                if (needed <= 0) return anyLevelChange;
                if (xp < needed) return anyLevelChange;

                int beforeLevel = level;

                // Try the internal "level up" routines (names vary by version/mappings)
                boolean invoked =
                        tryInvokeNoArgMethodAnyVisibility(vill, "increaseMerchantCareer") ||
                                tryInvokeNoArgMethodAnyVisibility(vill, "increaseProfessionLevel") ||
                                tryInvokeNoArgMethodAnyVisibility(vill, "levelUp") ||
                                tryInvokeNoArgMethodAnyVisibility(vill, "increaseProfessionLevelOnUpdate") ||
                                tryInvokeNoArgMethodAnyVisibility(vill, "increaseMerchantCareerOnUpdate");

                // Some mappings level-up by flagging and then calling updateTrades; try to help that path too.
                // (If it doesn't exist, it's a no-op.)
                tryInvokeNoArgMethodAnyVisibility(vill, "updateTrades");

                int afterLevel = beforeLevel;
                try { afterLevel = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}

                if (!invoked) {
                    EZVillagerReroll.LOG().debug("[EZVR] triggerVanillaLevelUpsIfNeeded: no known level-up method found (villager={} lvl={} xp={} needed={})",
                            vill.getUUID(), beforeLevel, xp, needed);
                    return anyLevelChange;
                }

                if (afterLevel <= beforeLevel) {
                    // We invoked *something* but level didn't change; avoid looping forever.
                    EZVillagerReroll.LOG().debug("[EZVR] triggerVanillaLevelUpsIfNeeded: invoked but level unchanged (villager={} lvl={} xp={} needed={})",
                            vill.getUUID(), beforeLevel, xp, needed);
                    return anyLevelChange;
                }

                anyLevelChange = true;
            }

            return anyLevelChange;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] triggerVanillaLevelUpsIfNeeded failed (soft): {}", t.toString());
            return false;
        }
    }

    /**
     * Vanilla-ish XP thresholds per level.
     * These have been stable for a long time; we use them only to decide whether we should poke level-up.
     */
    private static int getVanillaMaxXpForLevel(int level) {
        // Level 1->2, 2->3, 3->4, 4->5
        return switch (Math.max(1, Math.min(5, level))) {
            case 1 -> 10;
            case 2 -> 70;
            case 3 -> 150;
            case 4 -> 250;
            default -> 0;
        };
    }

    private static boolean tryInvokeNoArgMethodAnyVisibility(Object target, String name) {
        try {
            if (target == null || name == null) return false;

            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    m.invoke(target);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method m2 = c.getMethod(name);
                        m2.invoke(target);
                        return true;
                    } catch (NoSuchMethodException ignored2) {
                        // keep walking
                    }
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] tryInvokeNoArgMethodAnyVisibility failed name={} err={}", name, t.toString());
            return false;
        }
    }

    private static boolean addVillagerXpSafe(Villager vill, int add) {
        try {
            if (vill == null) return false;
            if (add <= 0) return true;

            // 1) Prefer "proper" XP methods if present (these may also trigger level-up logic depending on mapping)
            if (tryInvokeIntMethodAnyVisibility(vill, "increaseMerchantXp", add)) return true;
            if (tryInvokeIntMethodAnyVisibility(vill, "addVillagerXp", add)) return true;
            if (tryInvokeIntMethodAnyVisibility(vill, "addXp", add)) return true;
            if (tryInvokeIntMethodAnyVisibility(vill, "gainExperience", add)) return true;
            if (tryInvokeIntMethodAnyVisibility(vill, "addExperience", add)) return true;

            // 2) Fallback: setVillagerXp(getVillagerXp() + add)  (THIS is what your manual reroll fallback does)
            try {
                int cur = 0;
                try {
                    cur = Math.max(0, vill.getVillagerXp());
                } catch (Throwable ignored) {
                    cur = 0;
                }

                long next = (long) cur + (long) add;
                if (next < 0L) next = 0L;
                if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;

                if (tryInvokeIntMethodAnyVisibility(vill, "setVillagerXp", (int) next)) return true;
            } catch (Throwable ignored) {
                // keep going
            }

            // 3) Field fallback: try common field names (best-effort)
            String[] fields = new String[]{"villagerXp", "xp"};
            for (String fn : fields) {
                try {
                    var f = findFieldAnyVisibility(vill.getClass(), fn);
                    if (f == null) continue;

                    int cur = 0;
                    try { cur = Math.max(0, f.getInt(vill)); } catch (Throwable ignored) { cur = 0; }

                    long sum = (long) cur + (long) add;
                    if (sum < 0L) sum = 0L;
                    if (sum > Integer.MAX_VALUE) sum = Integer.MAX_VALUE;

                    f.setInt(vill, (int) sum);
                    return true;
                } catch (Throwable ignored) {
                    // try next
                }
            }

            EZVillagerReroll.LOG().warn("[EZVR] addVillagerXpSafe: no known XP method/field found; villager={} add={}",
                    vill.getUUID(), add);
            return false;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] addVillagerXpSafe failed (soft): {}", t.toString());
            return false;
        }
    }

    private static boolean tryInvokeIntMethodAnyVisibility(Object target, String name, int arg) {
        try {
            if (target == null || name == null) return false;

            Class<?> c = target.getClass();
            while (c != null && c != Object.class) {
                // Try declared (covers private/protected/package)
                try {
                    Method m = c.getDeclaredMethod(name, int.class);
                    m.setAccessible(true);
                    m.invoke(target, arg);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // Try public/inherited
                    try {
                        Method m2 = c.getMethod(name, int.class);
                        m2.invoke(target, arg);
                        return true;
                    } catch (NoSuchMethodException ignored2) {
                        // keep walking
                    } catch (Throwable invokeErr2) {
                        EZVillagerReroll.LOG().debug("[EZVR] tryInvokeIntMethodAnyVisibility: invoke failed name={} arg={} err={}",
                                name, arg, invokeErr2.toString());
                        return false;
                    }
                } catch (Throwable invokeErr) {
                    EZVillagerReroll.LOG().debug("[EZVR] tryInvokeIntMethodAnyVisibility: invoke failed name={} arg={} err={}",
                            name, arg, invokeErr.toString());
                    return false;
                }

                c = c.getSuperclass();
            }

            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.lang.reflect.Field findFieldAnyVisibility(Class<?> cls, String name) {
        try {
            if (cls == null || name == null) return null;

            Class<?> c = cls;
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Mask sanitation helper for "offersAtStart" context
    // ---------------------------------------------------------------------

    private static long sanitizeMaskForSize(long mask, int offerCount) {
        try {
            int n = Math.max(0, Math.min(63, offerCount));
            if (n <= 0) return 0L;
            long allowed = (1L << n) - 1L;
            return mask & allowed;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
