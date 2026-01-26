// neoforge\src\main\java\org\z2six\villageroverhaul\server\ServerEvents.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.customcommands.PacketCcOpenChestRules;
import org.z2six.villageroverhaul.network.customcommands.PacketCcOpenTeachMenu;
import org.z2six.villageroverhaul.network.customcommands.PacketCcWaitState;
import org.z2six.villageroverhaul.network.farming.PacketFarmingOverlayText;
import org.z2six.villageroverhaul.network.recruit.PacketOpenRecruitScreen;
import org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.ServerSync;
import org.z2six.villageroverhaul.logic.HoarderOffers;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.server.RespawnService;
import org.z2six.villageroverhaul.server.CustomCommandsService;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;
import org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService;
import org.z2six.villageroverhaul.server.ai.VillagerEatTestService;
import org.z2six.villageroverhaul.server.AutoTradeServerService;

import java.util.List;

public final class ServerEvents {

    private static volatile boolean registered = false;
    private static volatile long lastTickDebugGameTime = -1;
    private static volatile int lastSyncedCfgHash = Integer.MIN_VALUE;

    private ServerEvents() {}

    public static void register(IEventBus bus) {
        if (bus == null) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents.register called with null bus");
            return;
        }
        if (registered) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] ServerEvents.register called twice; ignoring.");
            return;
        }
        registered = true;

        try {
            bus.addListener(ServerEvents::onServerTickPost);
            bus.addListener(ServerEvents::onServerStarted);
            bus.addListener(ServerEvents::onServerStopping);
            bus.addListener(ServerEvents::onContainerOpen);

            // For Hoarder logic
            bus.addListener(ServerEvents::onContainerClose);

            // sync server config to players when they log in (fixes client tooltip mapping)
            bus.addListener(ServerEvents::onPlayerLoggedIn);

            // recruit RMB handler
            bus.addListener(ServerEvents::onEntityInteract);

            // respawn anchor RMB handler
            bus.addListener(ServerEvents::onRightClickBlock);

            // Custom Commands: chat triggers
            bus.addListener(ServerEvents::onServerChat);

            // villager/merchant stat initialization
            VillagerStatsEvents.register(bus);

            // Villager history counters
            VillagerHistoryEvents.register(bus);

            // Villager brain/module attach (AI goals)
            bus.addListener(VillagerBrain::onEntityJoinLevel);
            
            // Villager's on spawn event for CombatInventory
            VillagerCombatInventoryProbeEvents.register(bus);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ServerEvents registered on gameplay bus.");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents.register failed", t);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        try {
            if (e == null) return;
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;

            ServerSync.syncTo(sp);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onPlayerLoggedIn: synced config to {}", sp.getGameProfile().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onPlayerLoggedIn failed (soft): {}", t.toString());
        }
    }

    // RMB unemployed villager -> open recruit screen
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        try {
            if (e == null) return;
            if (e.getHand() != InteractionHand.MAIN_HAND) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (sp.connection == null) return;

            var level = sp.serverLevel();
            if (level == null || level.isClientSide()) return;

            // ============================================================
            // Custom Commands (teaching) intercepts
            // ============================================================
            try {
                Villager taught = resolveTeachingVillager(sp);
                if (taught != null && RecruitService.isRecruited(taught)
                        && org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(taught, sp)) {
                    var session = CustomCommandsService.getSessionFor(sp, taught);
                    if (session != null) {
                        // Record: entity interaction (only for kind=interact)
                        if (session.waitingKind == 0) {
                            if (CustomCommandsService.recordInteractEntity(sp, taught, e.getTarget())) {
                                try { level.playSound(null, taught.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.1f); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcWaitState(taught.getId(), false))); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketFarmingOverlayText("Recorded. Teach the Villager what to do...", 1000000))); } catch (Throwable ignored) {}
                                e.setCanceled(true);
                                e.setCancellationResult(InteractionResult.SUCCESS);
                                return;
                            }
                        }

                        // Right-click the taught villager to open the teach menu (only when not waiting).
                        if (e.getTarget() instanceof Villager v2 && v2.getUUID().equals(taught.getUUID())) {
                            if (session.waitingKind < 0) {
                                try { level.playSound(null, taught.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.8f, 1.0f); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcOpenTeachMenu(taught.getId()))); } catch (Throwable ignored) {}
                                e.setCanceled(true);
                                e.setCancellationResult(InteractionResult.SUCCESS);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            if (!(e.getTarget() instanceof Villager vill)) return;

            // Only unemployed villagers
            if (vill.getVillagerData().getProfession() != VillagerProfession.NONE) return;

            // If already recruited, do nothing (later we could open info)
            if (RecruitService.isRecruited(vill)) return;

            // Compute cost + open GUI
            int cost = RecruitService.computeRecruitCost(vill);

            sp.connection.send(new ClientboundCustomPayloadPacket(
                    new PacketOpenRecruitScreen(vill.getId(), true, false, cost, "")
            ));

            // Consume interaction so other mods / vanilla doesn't do anything weird.
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Opened recruit screen for player={} villager={} cost={}",
                    sp.getGameProfile().getName(), vill.getUUID(), cost);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onEntityInteract failed (soft): {}", t.toString());
        }
    }

    private static void onContainerOpen(PlayerContainerEvent.Open e) {
        try {
            if (e == null) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            try { ServerSync.syncTo(sp); } catch (Throwable ignored) {}

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof AbstractVillager merchant)) return;

            trySendVillagerStatsSnapshot(sp, merchant);

            if (!(merchant instanceof Villager vill)) return;

            // ============================================================
            // Pause patrol movement while trading GUI is open
            // ============================================================
            try {
                if (org.z2six.villageroverhaul.server.ai.VillagerBrain.getMode(vill)
                        == org.z2six.villageroverhaul.server.ai.VillagerBrain.Mode.PATROL) {
                    org.z2six.villageroverhaul.server.ai.VillagerBrain.setPatrolPaused(vill, true);
                }
                org.z2six.villageroverhaul.server.ai.VillagerBrain.setUiPaused(vill, true);
            } catch (Throwable ignored) {}

            // If busy (task OR settlement), do NOT touch offers.
            // Auto-search must be reversible back to snapshot without other systems mutating trades.
            if (SearchService.isBusy(vill)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] onContainerOpen: villager busy; skipping hoarder/gen (villager={} player={})",
                        vill.getUUID(), sp.getGameProfile().getName());
                return;
            }

            // If awaiting payment, DO NOT touch offers (settlement UI relies on exact state).
            if (SearchService.isAwaitingPayment(vill)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] onContainerOpen: villager awaiting payment; skipping hoarder/gen (villager={} player={})",
                        vill.getUUID(), sp.getGameProfile().getName());
                return;
            }

            // Enforce Hoarder immediately so the UI opens with the correct offer count.
            try {
                HoarderOffers.normalizeOffers(vill, sp);
            } catch (Throwable ignored) {}

            // Enforce Generosity immediately so prices are correct.
            try {
                boolean genChanged = VillagerGenerosityOfferService.normalizeAndApply(vill);
                if (genChanged) {
                    try {
                        sp.sendMerchantOffers(
                                menu.containerId,
                                vill.getOffers(),
                                vill.getVillagerData().getLevel(),
                                vill.getVillagerXp(),
                                vill.showProgressBar(),
                                vill.canRestock()
                        );
                    } catch (Throwable ignored2) {}
                }
            } catch (Throwable ignored) {}

            // Start the “while menu open” enforcement loop.
            try {
                HoarderOfferService.onMerchantMenuOpen(sp, menu, vill);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onContainerOpen failed", t);
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        try {
            if (e == null) return;
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (sp.connection == null) return;
            if (e.getHand() != InteractionHand.MAIN_HAND) return;

            var level = sp.serverLevel();
            if (level == null || level.isClientSide()) return;

            // ============================================================
            // Custom Commands (teaching) intercepts
            // ============================================================
            try {
                Villager taught = resolveTeachingVillager(sp);
                if (taught != null && RecruitService.isRecruited(taught)
                        && org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(taught, sp)) {
                    var session = CustomCommandsService.getSessionFor(sp, taught);
                    if (session != null) {
                        if (session.waitingKind == 0) {
                            if (CustomCommandsService.recordInteractBlock(sp, taught, level, e.getPos())) {
                                try { level.playSound(null, taught.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.1f); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcWaitState(taught.getId(), false))); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketFarmingOverlayText("Recorded. Teach the Villager what to do...", 1000000))); } catch (Throwable ignored) {}
                                // Cancel block use so we don't accidentally place/use while recording.
                                e.setCanceled(true);
                                e.setCancellationResult(InteractionResult.SUCCESS);
                                return;
                            }
                        } else if (session.waitingKind == 1) {
                            if (CustomCommandsService.recordWithdrawChest(sp, taught, level, e.getPos())) {
                                try { level.playSound(null, taught.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.1f); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcWaitState(taught.getId(), false))); } catch (Throwable ignored) {}
                                int si = CustomCommandsService.getSessionLastStepIndex(sp, taught);
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketFarmingOverlayText("Select items to withdraw...", 1000000))); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcOpenChestRules(taught.getId(), si, 1))); } catch (Throwable ignored) {}
                                e.setCanceled(true);
                                e.setCancellationResult(InteractionResult.SUCCESS);
                                return;
                            }
                        } else if (session.waitingKind == 2) {
                            if (CustomCommandsService.recordDepositChest(sp, taught, level, e.getPos())) {
                                try { level.playSound(null, taught.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.1f); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcWaitState(taught.getId(), false))); } catch (Throwable ignored) {}
                                int si = CustomCommandsService.getSessionLastStepIndex(sp, taught);
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketFarmingOverlayText("Select items to deposit...", 1000000))); } catch (Throwable ignored) {}
                                try { sp.connection.send(new ClientboundCustomPayloadPacket(new PacketCcOpenChestRules(taught.getId(), si, 2))); } catch (Throwable ignored) {}
                                e.setCanceled(true);
                                e.setCancellationResult(InteractionResult.SUCCESS);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            var pos = e.getPos();
            if (pos == null) return;
            if (level.getBlockState(pos).getBlock() != Blocks.RESPAWN_ANCHOR) return;

            // Require holding an emerald (as requested).
            ItemStack held = sp.getMainHandItem();
            if (held == null || held.isEmpty() || !held.is(net.minecraft.world.item.Items.EMERALD)) return;

            List<RespawnSavedData.Snapshot> snaps = RespawnService.listForOwner(sp, level);
            List<PacketOpenRespawnAnchorScreen.Entry> entries = new java.util.ArrayList<>();
            for (RespawnSavedData.Snapshot s : snaps) {
                if (s == null || s.respawnId == null) continue;
                int cost = RespawnService.computeRespawnCost(s.recruitCostAtDeath);
                entries.add(new PacketOpenRespawnAnchorScreen.Entry(
                        s.respawnId,
                        s.nameJson == null ? "" : s.nameJson,
                        s.professionId == null ? "" : s.professionId,
                        cost,
                        Math.max(0, s.deaths)
                ));
            }

            sp.connection.send(new ClientboundCustomPayloadPacket(
                    new PacketOpenRespawnAnchorScreen(pos.getX(), pos.getY(), pos.getZ(), entries)
            ));

            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onRightClickBlock failed (soft): {}", t.toString());
        }
    }

    private static void onServerChat(ServerChatEvent e) {
        try {
            if (e == null) return;
            if (!(e.getPlayer() instanceof ServerPlayer sp)) return;
            if (sp.serverLevel() == null) return;

            String raw = "";
            try { raw = e.getMessage().getString(); } catch (Throwable ignored) { raw = ""; }
            if (raw == null) raw = "";
            String msg = raw.trim();
            if (msg.isEmpty()) return;
            if (msg.startsWith("/")) return;

            var level = sp.serverLevel();

            final double radius = Math.max(1.0, (double) ServerConfig.customCommandsChatRadius);
            double bestDist2 = Double.MAX_VALUE;
            Villager bestVill = null;
            int bestActionIdx = -1;

            for (Villager vill : level.getEntitiesOfClass(Villager.class, sp.getBoundingBox().inflate(radius))) {
                if (vill == null) continue;
                if (!RecruitService.isRecruited(vill)) continue;
                if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;
                if (CustomCommandsService.isExecuting(vill)) continue;
                if (CustomCommandsService.isVillagerTeaching(vill)) continue;

                var actions = CustomCommandsService.getActionsMeta(vill);
                if (actions == null || actions.isEmpty()) continue;

                for (int i = 0; i < actions.size(); i++) {
                    var a = actions.get(i);
                    if (a == null) continue;
                    if (!CustomCommandsService.matchesCommand(a, msg)) continue;

                    double d2 = vill.distanceToSqr(sp);
                    if (d2 < bestDist2) {
                        bestDist2 = d2;
                        bestVill = vill;
                        bestActionIdx = i;
                    }
                }
            }

            if (bestVill != null && bestActionIdx >= 0) {
                long now = level.getGameTime();
                if (CustomCommandsService.canStartAction(bestVill, bestActionIdx, now)) {
                    CustomCommandsService.startExecution(bestVill, bestActionIdx);
                } else {
                    // Queue a retry without requiring the player to re-send the chat message.
                    var meta = CustomCommandsService.getActionMeta(bestVill, bestActionIdx);
                    long delayUntil = now;
                    try {
                        if (meta != null) {
                            long lastFail = meta.lastFailGameTime();
                            long retryTicks = Math.max(0L, (long) meta.retryAfterSeconds() * 20L);
                            if (lastFail > 0L && retryTicks > 0L) delayUntil = Math.max(now, lastFail + retryTicks);
                        }
                    } catch (Throwable ignored) {}
                    CustomCommandsService.queueExecution(bestVill, bestActionIdx, delayUntil);
                }
            }

        } catch (Throwable ignored) {}
    }

    private static Villager resolveTeachingVillager(ServerPlayer sp) {
        try {
            if (sp == null) return null;
            CustomCommandsService.TeachSession s = CustomCommandsService.getSession(sp);
            if (s == null) return null;
            try {
                Entity ent = sp.serverLevel().getEntity(s.villagerEntityId);
                if (ent instanceof Villager v && v.getUUID().equals(s.villagerUuid)) return v;
            } catch (Throwable ignored) {}

            // Fallback: resolve by UUID across all levels (in case of chunk/dimension quirks).
            try {
                if (sp.server != null) {
                    for (var lvl : sp.server.getAllLevels()) {
                        try {
                            Entity ent = lvl.getEntity(s.villagerUuid);
                            if (ent instanceof Villager v && v.getUUID().equals(s.villagerUuid)) return v;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void trySendVillagerStatsSnapshot(ServerPlayer sp, AbstractVillager merchant) {
        try {
            if (sp == null || sp.connection == null) return;
            if (merchant == null) return;

            VillagerStatsService.ensureStats(merchant);

            int id = merchant.getId();

            CompoundTag pd = merchant.getPersistentData();
            if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                sp.connection.send(new ClientboundCustomPayloadPacket(PacketVillagerStatsData.missing(id)));
                return;
            }

            CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);

            int g  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
            int t  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
            int i  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
            int h  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));

            int vit = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_VITALITY));
            int agi = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_AGILITY));
            int str = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_STRENGTH));
            int arm = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_ARMOR));

            int mot = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_MOTIVATION));
            int eff = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_EFFICIENCY));
            int pw  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_PLANT_WHISPERER));
            int rng = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_RANGER));

            sp.connection.send(new ClientboundCustomPayloadPacket(
                    new PacketVillagerStatsData(id, true, g, t, i, h, vit, agi, str, arm, mot, eff, pw, rng)
            ));

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Sent villager stats snapshot to {} for entityId={} uuid={}",
                    sp.getGameProfile().getName(), id, merchant.getUUID());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] trySendVillagerStatsSnapshot failed (soft): {}", t.toString());
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post e) {
        try {
            if (e == null) return;

            MinecraftServer server;
            try {
                server = e.getServer();
            } catch (Throwable ignored) {
                return;
            }
            if (server == null) return;

            // Hot-sync server config changes to all players (covers server config reload while clients are connected).
            try {
                int h = org.z2six.villageroverhaul.config.ServerConfig.cfgHash();
                if (h != lastSyncedCfgHash) {
                    lastSyncedCfgHash = h;
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        try { ServerSync.syncTo(sp); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            try {
                SearchService.tick(server);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents: SearchService.tick failed", t);
            }

            try {
                HoarderOfferService.tick(server);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] ServerEvents: HoarderOfferService.tick failed (soft): {}", t.toString());
            }

            try {
                VillagerBrain.tickForceBlocks();
            } catch (Throwable ignored) {}

            try {
                VillagerBrain.tickManualPlantAnimations();
            } catch (Throwable ignored) {}

            try {
                VillagerCombatLoadoutService.tick(server);
            } catch (Throwable ignored) {}

            try {
                VillagerHistoryService.tick(server);
            } catch (Throwable ignored) {}

            try {
                VillagerEatTestService.tick(server);
            } catch (Throwable ignored) {}

            try {
                AutoTradeServerService.tick(server);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents: AutoTradeServerService.tick failed", t);
            }

            try {
                long gt = server.overworld().getGameTime();
                if (gt % 200L == 0L && gt != lastTickDebugGameTime) {
                    lastTickDebugGameTime = gt;
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Server tick heartbeat (gameTime={})", gt);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents.onServerTickPost failed", t);
        }
    }

    private static void onServerStarted(ServerStartedEvent e) {
        try {
            if (e == null) return;
            MinecraftServer server = e.getServer();
            if (server == null) return;

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ServerStarted: loading persisted auto-search tasks.");
            try {
                SearchSavedData.loadIntoSearchService(server);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerStarted: loadIntoSearchService failed", t);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents.onServerStarted failed", t);
        }
    }

    private static void onServerStopping(ServerStoppingEvent e) {
        try {
            if (e == null) return;
            MinecraftServer server = e.getServer();
            if (server == null) return;

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ServerStopping: saving persisted auto-search tasks.");
            try {
                SearchSavedData.saveFromSearchService(server);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerStopping: saveFromSearchService failed", t);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents.onServerStopping failed", t);
        }
    }

    private static void onContainerClose(PlayerContainerEvent.Close e) {
        try {
            if (e == null) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            try {
                HoarderOfferService.onMerchantMenuClose(sp);
            } catch (Throwable ignored) {}

            // ============================================================
            // Resume patrol movement when trade menu closes
            // ============================================================
            try {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Villager vill) {
                    if (org.z2six.villageroverhaul.server.ai.VillagerBrain.getMode(vill)
                            == org.z2six.villageroverhaul.server.ai.VillagerBrain.Mode.PATROL) {
                        org.z2six.villageroverhaul.server.ai.VillagerBrain.setPatrolPaused(vill, false);
                    }
                    org.z2six.villageroverhaul.server.ai.VillagerBrain.setUiPaused(vill, false);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onContainerClose failed (soft): {}", t.toString());
        }
    }
}
