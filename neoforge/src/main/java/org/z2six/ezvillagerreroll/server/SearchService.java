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
                    snapshotLockMaskSafe(vill)
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
             long lockMaskBefore
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
            this.lockMaskBefore = lockMaskBefore;
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
                   List<String> requestedTargets
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

                    Task t = new Task(
                            td.villagerUuid,
                            td.villagerEntityId,
                            td.ownerPlayerUuid,
                            td.requested,
                            started,
                            next,
                            cd,
                            before,
                            lockMaskBefore
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
                            sd.requestedTargets == null ? List.of() : sd.requestedTargets
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

            EZVillagerReroll.LOG().info("[EZVR] Auto-search START: player={} villager={} entityId={} requested={} cooldownTicks={} lockMaskBefore={} offersBefore={}",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    vill.getId(),
                    t.requestedKeys.size(),
                    cooldown,
                    Long.toUnsignedString(t.lockMaskBefore),
                    t.offersBeforeTag == null ? -1 : t.offersBeforeTag.size()
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
                EZVillagerReroll.LOG().info("[EZVR] Auto-search CANCEL: player={} villager={} entityId={}",
                        sp.getGameProfile().getName(), vill.getUUID(), vill.getId());
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
                                    settle.requestedTargets
                            )
                    ));

                    EZVillagerReroll.LOG().debug("[EZVR] openBusyScreen: sent PacketOpenAutoSearchPaymentScreen (player={} villagerEntityId={} hourly={} final={} elapsedTicks={} payOffers={} declineOffers={} lockMaskBefore={} requestedTargets={})",
                            sp.getGameProfile().getName(),
                            vill.getId(),
                            settle.hourlyCost,
                            settle.finalCost,
                            elapsedTicks,
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
                    EZVillagerReroll.LOG().info("[EZVR] Auto-search DONE (already matched): villager={} entityId={} requestedKeys={}",
                            vill.getUUID(), vill.getId(), task.requestedKeys.size());
                    it.remove();

                    trySetVillagerGlow(vill, false);
                    createSettlementAndNotify(server, vill, task, now);
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
                    requested
            );

            SETTLEMENTS.put(vill.getUUID(), settle);

            EZVillagerReroll.LOG().info("[EZVR] Auto-search SETTLEMENT created: villager={} entityId={} hourly={} elapsedTicks={} finalCost={} owner={} payOffers={} declineOffers={} lockMaskBefore={} requestedTargets={}",
                    vill.getUUID(), vill.getId(), hourly, elapsedTicks, finalCost, String.valueOf(task.ownerPlayerUuid),
                    payOffers == null ? -1 : payOffers.size(),
                    declineOffers == null ? -1 : declineOffers.size(),
                    Long.toUnsignedString(task.lockMaskBefore),
                    requested.size()
            );

            notifyOwnerDone(server, task);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] createSettlementAndNotify failed", t);
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

    public static Settlement popSettlement(UUID villagerUuid) {
        try {
            if (villagerUuid == null) return null;
            return SETTLEMENTS.remove(villagerUuid);
        } catch (Throwable t) {
            return null;
        }
    }
}
