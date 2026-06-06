// neoforge\src\main\java\org\z2six\villageroverhaul\server\ServerEvents.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
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
import org.z2six.villageroverhaul.content.ModItems;
import org.z2six.villageroverhaul.server.RespawnService;
import org.z2six.villageroverhaul.server.CustomCommandsService;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;
import org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService;
import org.z2six.villageroverhaul.server.ai.VillagerEatTestService;
import org.z2six.villageroverhaul.server.AutoTradeServerService;
import org.z2six.villageroverhaul.network.naming.PacketOpenVillagerLastNameScreen;

import java.util.List;
import java.util.UUID;

public final class ServerEvents {
    private static final AABB GLOBAL_ENTITY_AABB = new AABB(-3.0E7, -2048.0, -3.0E7, 3.0E7, 4096.0, 3.0E7);

    private static volatile boolean registered = false;
    private static volatile long lastTickDebugGameTime = -1;
    private static volatile int lastSyncedCfgHash = Integer.MIN_VALUE;

    private record ChatAudience(boolean global, int range) {}
    private record VillagerChatFeedback(Villager vill, boolean whisper, String text) {}
    private record MacroMatch(Villager villager, int actionIndex) {}

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
            bus.addListener(ServerEvents::onLivingIncomingDamage);

            // villager/merchant stat initialization
            VillagerStatsEvents.register(bus);

            // Villager history counters
            VillagerHistoryEvents.register(bus);

            // Villager naming
            VillagerNamingEvents.register(bus);

            // Villager brain/module attach (AI goals)
            bus.addListener(VillagerBrain::onEntityJoinLevel);
            bus.addListener(VillagerReleaseService::onEntityJoinLevel);
            
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

            try {
                Entity target = e.getTarget();
                ItemStack held = sp.getMainHandItem();
                if (target != null
                        && held != null
                        && held.getItem() == Items.WHITE_DYE
                        && sp.isShiftKeyDown()) {
                    boolean ignoredNow = IgnoredTargetService.toggleIgnoredByVillagers(target);
                    sendOverlayText(sp, ignoredNow ? "Target ignored by villagers" : "Target no longer ignored", 1800);
                    e.setCanceled(true);
                    e.setCancellationResult(InteractionResult.SUCCESS);
                    return;
                }
            } catch (Throwable ignored) {}

            // ============================================================
            // Patrol setup: block vanilla Merchant interaction while recording
            // (Dedicated server: otherwise server opens Merchant UI and closes our patrol screens)
            // ============================================================
            try {
                if (e.getTarget() instanceof Villager pv
                        && RecruitService.isRecruited(pv)
                        && org.z2six.villageroverhaul.server.ai.VillagerBrain.getMode(pv) == org.z2six.villageroverhaul.server.ai.VillagerBrain.Mode.PATROL_SETUP) {
                    java.util.UUID owner = org.z2six.villageroverhaul.server.ai.VillagerBrain.getPatrolSetupOwner(pv);
                    if (owner != null && owner.equals(sp.getUUID())) {
                        try { pv.setTradingPlayer(null); } catch (Throwable ignored) {}
                        e.setCanceled(true);
                        e.setCancellationResult(InteractionResult.SUCCESS);
                        return;
                    }
                }
            } catch (Throwable ignored) {}

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
                                if (session.pendingLookDurationStepIndex >= 0) {
                                    try { level.playSound(null, taught.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.8f, 1.0f); } catch (Throwable ignored) {}
                                    try {
                                        sp.connection.send(new ClientboundCustomPayloadPacket(
                                                new org.z2six.villageroverhaul.network.customcommands.PacketCcOpenLookDuration(
                                                        taught.getId(), session.pendingLookDurationStepIndex
                                                )
                                        ));
                                    } catch (Throwable ignored) {}
                                    e.setCanceled(true);
                                    e.setCancellationResult(InteractionResult.SUCCESS);
                                    return;
                                }
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

            if (tryOpenLastNameScreen(sp, vill)) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }

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

    private static boolean tryOpenLastNameScreen(ServerPlayer sp, Villager vill) {
        try {
            if (sp == null || vill == null || sp.connection == null) return false;
            ItemStack held = sp.getMainHandItem();
            if (held == null || !held.is(ModItems.FAMILY_NAME_DEED.get())) return false;

            if (!RecruitService.isRecruited(vill)) {
                sendOverlayText(sp, "Use this on one of your recruited villagers", 2200);
                return true;
            }
            if (!VillagerAccessGate.canUseControls(vill, sp)) {
                sendOverlayText(sp, "You can only rename your own villagers", 2200);
                return true;
            }

            List<String> lastNames = VillagerFamilyTreeService.collectUniqueLastNames(vill);
            String currentLastName = VillagerNameStateService.getTrackedLastName(vill);
            VillagerBrain.setUiPaused(vill, true);
            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            try { vill.setTradingPlayer(null); } catch (Throwable ignored) {}

            sp.connection.send(new ClientboundCustomPayloadPacket(new PacketOpenVillagerLastNameScreen(
                    vill.getId(),
                    currentLastName == null ? "" : currentLastName,
                    lastNames,
                    ""
            )));
            try { vill.level().playSound(null, vill.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.7f, 1.1f); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tryOpenLastNameScreen failed (soft): {}", t.toString());
            return false;
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

            // Require holding the configured currency item.
            ItemStack held = sp.getMainHandItem();
            if (held == null || held.isEmpty() || !org.z2six.villageroverhaul.logic.PaymentUtil.matchesCost(held)) return;

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

            // Whisper / Shout parsing (prefix-based, similar to localized chat mods).
            boolean isShout = false;
            boolean isWhisper = false;
            String content = msg;
            try {
                String spfx = ServerConfig.shoutPrefix == null ? "!" : ServerConfig.shoutPrefix;
                if (spfx.isBlank()) spfx = "!";
                String wpfx = ServerConfig.whisperPrefix == null ? "#" : ServerConfig.whisperPrefix;
                if (wpfx.isBlank()) wpfx = "#";

                if (ServerConfig.shoutEnabled && content.startsWith(spfx)) {
                    String c = content.substring(spfx.length()).trim();
                    if (!c.isEmpty()) {
                        isShout = true;
                        content = c;
                    }
                } else if (ServerConfig.whisperEnabled && content.startsWith(wpfx)) {
                    String c = content.substring(wpfx.length()).trim();
                    if (!c.isEmpty()) {
                        isWhisper = true;
                        content = c;
                    }
                }
            } catch (Throwable ignored) {}

            if (isShout) {
                int cost = Math.max(0, ServerConfig.shoutHungerCost);
                try {
                    if (cost > 0 && sp.getFoodData() != null) {
                        int have = sp.getFoodData().getFoodLevel();
                        if (have < cost) {
                            try {
                                sp.connection.send(new ClientboundSystemChatPacket(
                                        Component.literal("Not enough hunger to shout.").withStyle(ChatFormatting.RED),
                                        false
                                ));
                            } catch (Throwable ignored) {}
                            try { e.setCanceled(true); } catch (Throwable ignored) {}
                            return;
                        }
                        sp.getFoodData().setFoodLevel(Math.max(0, have - cost));
                    }
                } catch (Throwable ignored) {}
            }

            ChatAudience audience = resolveChatAudience(isShout, isWhisper);
            java.util.ArrayList<VillagerChatFeedback> villagerFeedback = new java.util.ArrayList<>();

            // Chat commands (module-level) have priority over per-villager taught macros.
            boolean handled = tryHandlePlayerChatCommands(sp, content, audience, isWhisper, villagerFeedback);

            // Only try taught macros if no module-level chat command matched.
            if (!handled) {
                java.util.ArrayList<Villager> starters = new java.util.ArrayList<>();
                for (Villager vill : collectVillagersForAudience(sp, audience)) {
                    if (vill == null) continue;
                    if (!RecruitService.isRecruited(vill)) continue;
                    if (!CustomCommandsService.isChatListening(vill)) continue;
                    if (CustomCommandsService.isExecuting(vill)) continue;
                    if (CustomCommandsService.isVillagerTeaching(vill)) continue;
                    starters.add(vill);
                }

                java.util.LinkedHashMap<UUID, MacroMatch> matches = new java.util.LinkedHashMap<>();
                java.util.HashSet<UUID> relayers = new java.util.HashSet<>();

                for (Villager vill : starters) {
                    int actionIdx = findMatchingMacroAction(vill, sp, content, false);
                    if (actionIdx >= 0) addMacroMatch(matches, vill, actionIdx);
                }

                if (!starters.isEmpty()) {
                    java.util.HashSet<java.util.UUID> seen = new java.util.HashSet<>();
                    java.util.ArrayDeque<Villager> q = new java.util.ArrayDeque<>();
                    for (Villager v : starters) {
                        if (v == null) continue;
                        seen.add(v.getUUID());
                        q.add(v);
                    }

                    int safety = 0;
                    while (!q.isEmpty() && seen.size() < 512 && safety++ < 2000) {
                        Villager cur = q.poll();
                        if (cur == null) continue;

                        int actionIdx = findMatchingMacroAction(cur, sp, content, true);
                        if (actionIdx >= 0) addMacroMatch(matches, cur, actionIdx);

                        // Expand graph only if THIS villager is allowed to pass chat commands further.
                        if (!CustomCommandsService.isChatPassing(cur)) continue;

                        int passRange = audienceRangeClamp(audience);

                        boolean relayed = false;
                        var curLevel = (ServerLevel) cur.level();
                        for (Villager next : curLevel.getEntitiesOfClass(Villager.class, cur.getBoundingBox().inflate(passRange))) {
                            if (next == null) continue;
                            java.util.UUID id = next.getUUID();
                            if (id == null || seen.contains(id)) continue;

                            if (!RecruitService.isRecruited(next)) continue;
                            if (!CustomCommandsService.isChatListening(next)) continue;
                            if (CustomCommandsService.isExecuting(next)) continue;
                            if (CustomCommandsService.isVillagerTeaching(next)) continue;

                            seen.add(id);
                            q.add(next);
                            relayed = true;
                        }
                        if (relayed) {
                            relayers.add(cur.getUUID());
                            queueVillagerChatFeedback(villagerFeedback, cur, isWhisper, relayTextForMacro());
                        }
                    }
                }

                for (MacroMatch match : matches.values()) {
                    Villager targetVill = match.villager();
                    int targetActionIdx = match.actionIndex();
                    if (targetVill == null || targetActionIdx < 0) continue;

                    long now = level.getGameTime();
                    if (CustomCommandsService.canStartAction(targetVill, targetActionIdx, now)) {
                        CustomCommandsService.startExecution(targetVill, targetActionIdx);
                    } else {
                        // Queue a retry without requiring the player to re-send the chat message.
                        var meta = CustomCommandsService.getActionMeta(targetVill, targetActionIdx);
                        long delayUntil = now;
                        try {
                            if (meta != null) {
                                long lastFail = meta.lastFailGameTime();
                                long retryTicks = Math.max(0L, (long) meta.retryAfterSeconds() * 20L);
                                if (lastFail > 0L && retryTicks > 0L) delayUntil = Math.max(now, lastFail + retryTicks);
                            }
                        } catch (Throwable ignored) {}
                        CustomCommandsService.queueExecution(targetVill, targetActionIdx, delayUntil);
                    }

                    queueVillagerChatFeedback(villagerFeedback, targetVill, isWhisper, confirmTextForMacro());
                    if (relayers.contains(targetVill.getUUID())) {
                        queueVillagerChatFeedback(villagerFeedback, targetVill, isWhisper, relayTextForMacro());
                    }
                }
            }

            // Shout: server-wide broadcast, consumes hunger, orange.
            if (isShout) {
                Component line = Component.translatable("chat.type.text", sp.getDisplayName(), Component.literal(content))
                        .withStyle(ChatFormatting.GOLD);
                try { e.setCanceled(true); } catch (Throwable ignored) {}

                for (ServerPlayer other : sp.server.getPlayerList().getPlayers()) {
                    if (other == null || other.connection == null) continue;
                    try { other.connection.send(new ClientboundSystemChatPacket(line, false)); } catch (Throwable ignored) {}
                }
                flushVillagerChatFeedback(villagerFeedback);
                return;
            }

            // Whisper: localized (3D sphere), gray italics.
            if (isWhisper) {
                int r = Math.max(1, ServerConfig.whisperRange);
                double r2 = (double) r * (double) r;

                Component line = Component.translatable("chat.type.text", sp.getDisplayName(), Component.literal(content))
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);

                try { e.setCanceled(true); } catch (Throwable ignored) {}

                for (ServerPlayer other : level.players()) {
                    if (other == null || other.connection == null) continue;
                    try {
                        if (other.distanceToSqr(sp) > r2) continue;
                    } catch (Throwable ignored) {}
                    try {
                        other.connection.send(new ClientboundSystemChatPacket(line, false));
                    } catch (Throwable ignored) {}
                }
                flushVillagerChatFeedback(villagerFeedback);
                return;
            }

            // Localized chat: cancel vanilla broadcast and resend to nearby players (3D sphere).
            if (ServerConfig.localizedChatEnabled) {
                int r = Math.max(1, ServerConfig.localizedChatRange);
                double r2 = (double) r * (double) r;

                Component line;
                try {
                    Component body = e.getMessage();
                    // If we stripped a prefix, preserve the stripped content.
                    if (isShout || isWhisper) body = Component.literal(content);
                    if (body == null) body = Component.literal(content);
                    line = Component.translatable("chat.type.text", sp.getDisplayName(), body);
                } catch (Throwable ignored) {
                    line = Component.literal(sp.getGameProfile().getName() + ": " + content);
                }

                try { e.setCanceled(true); } catch (Throwable ignored) {}

                for (ServerPlayer other : level.players()) {
                    if (other == null || other.connection == null) continue;
                    try {
                        if (other.distanceToSqr(sp) > r2) continue;
                    } catch (Throwable ignored) {}
                    try {
                        other.connection.send(new ClientboundSystemChatPacket(line, false));
                    } catch (Throwable ignored) {}
                }
                flushVillagerChatFeedback(villagerFeedback);
                return;
            }

            Component line;
            try {
                line = Component.translatable("chat.type.text", sp.getDisplayName(), e.getMessage() == null ? Component.literal(content) : e.getMessage());
            } catch (Throwable ignored) {
                line = Component.literal(sp.getGameProfile().getName() + ": " + content);
            }
            try { e.setCanceled(true); } catch (Throwable ignored) {}
            for (ServerPlayer other : sp.server.getPlayerList().getPlayers()) {
                if (other == null || other.connection == null) continue;
                try { other.connection.send(new ClientboundSystemChatPacket(line, false)); } catch (Throwable ignored) {}
            }
            flushVillagerChatFeedback(villagerFeedback);

        } catch (Throwable ignored) {}
    }

    private static ChatAudience resolveChatAudience(boolean isShout, boolean isWhisper) {
        if (isShout) return new ChatAudience(true, Integer.MAX_VALUE);
        if (isWhisper) return new ChatAudience(false, Math.max(1, ServerConfig.whisperRange));
        if (ServerConfig.localizedChatEnabled) return new ChatAudience(false, Math.max(1, ServerConfig.localizedChatRange));
        return new ChatAudience(true, Integer.MAX_VALUE);
    }

    private static int audienceRangeClamp(ChatAudience audience) {
        if (audience == null) return Math.max(1, ServerConfig.customCommandsChatRadius);
        if (audience.global()) return Math.max(1, ServerConfig.customCommandsChatRadius);
        return Math.max(1, audience.range());
    }

    private static List<Villager> collectVillagersForAudience(ServerPlayer sp, ChatAudience audience) {
        try {
            if (sp == null || sp.server == null || sp.serverLevel() == null) return List.of();
            if (audience == null) audience = new ChatAudience(false, Math.max(1, ServerConfig.customCommandsChatRadius));

            java.util.ArrayList<Villager> out = new java.util.ArrayList<>();
            if (audience.global()) {
                for (ServerLevel level : sp.server.getAllLevels()) {
                    if (level == null) continue;
                    out.addAll(level.getEntitiesOfClass(Villager.class, GLOBAL_ENTITY_AABB));
                }
            } else {
                int range = Math.max(1, audience.range());
                out.addAll(sp.serverLevel().getEntitiesOfClass(Villager.class, sp.getBoundingBox().inflate(range)));
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static boolean tryHandlePlayerChatCommands(ServerPlayer sp, String msg, ChatAudience audience, boolean whisperFeedback, List<VillagerChatFeedback> villagerFeedback) {
        try {
            if (sp == null || msg == null) return false;
            if (sp.server == null) return false;
            if (sp.serverLevel() == null) return false;

            org.z2six.villageroverhaul.server.PlayerChatCommandsSavedData sd =
                    org.z2six.villageroverhaul.server.PlayerChatCommandsSavedData.get(sp.server);
            org.z2six.villageroverhaul.server.PlayerChatCommandsSavedData.Config cfg = sd.getOrCreate(sp.getUUID());
            if (cfg == null) return false;

            String m = msg.trim();
            if (m.isEmpty()) return false;

            boolean chain = cfg.chain;
            boolean caseSensitive = cfg.caseSensitive;

            if (ServerConfig.enableCombatModule && matches(cfg.help, m, caseSensitive)) {
                String relayText = relayTextForHelp();
                String confirmText = confirmTextForHelp();
                java.util.HashSet<UUID> relayers = new java.util.HashSet<>();
                var targets = collectOwnedVillagersForChat(sp, audience, chain, relayers);
                if (targets.isEmpty()) return false;

                try { org.z2six.villageroverhaul.server.HelpChatCommandService.activateFromPlayerContext(sp); } catch (Throwable ignored) {}
                for (Villager v : targets) {
                    try {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.combatHelp(v)) {
                            queueVillagerChatFeedback(villagerFeedback, v, whisperFeedback, confirmText);
                            if (relayers.contains(v.getUUID())) {
                                queueVillagerChatFeedback(villagerFeedback, v, whisperFeedback, relayText);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                return true;
            }

            if (matches(cfg.stopMacro, m, caseSensitive)) return applyStopMacro(sp, audience, chain, whisperFeedback, villagerFeedback, relayTextForStopMacro(), confirmTextForStopMacro());

            if (ServerConfig.enableCombatModule && matches(cfg.equip, m, caseSensitive)) return applyLoadoutSwap(sp, audience, chain, true, whisperFeedback, villagerFeedback, relayTextForLoadout(true), confirmTextForLoadout(true));
            if (ServerConfig.enableCombatModule && matches(cfg.stash, m, caseSensitive)) return applyLoadoutSwap(sp, audience, chain, false, whisperFeedback, villagerFeedback, relayTextForLoadout(false), confirmTextForLoadout(false));

            if (matches(cfg.neutral, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.NEUTRAL, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.NEUTRAL), confirmTextForMode(ModeSwitch.NEUTRAL));
            if (matches(cfg.idle, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.IDLE, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.IDLE), confirmTextForMode(ModeSwitch.IDLE));
            if (matches(cfg.follow, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.FOLLOW, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.FOLLOW), confirmTextForMode(ModeSwitch.FOLLOW));
            if (matches(cfg.patrol, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.PATROL, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.PATROL), confirmTextForMode(ModeSwitch.PATROL));
            if (ServerConfig.enableFarmingModule && matches(cfg.manualFarming, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.MANUAL_FARMING, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.MANUAL_FARMING), confirmTextForMode(ModeSwitch.MANUAL_FARMING));
            if (ServerConfig.enableCombatModule && matches(cfg.flee, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.FLEE, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.FLEE), confirmTextForMode(ModeSwitch.FLEE));
            if (ServerConfig.enableCombatModule && matches(cfg.defend, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.DEFEND, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.DEFEND), confirmTextForMode(ModeSwitch.DEFEND));
            if (ServerConfig.enableCombatModule && matches(cfg.aggressive, m, caseSensitive)) return applyModeSwitch(sp, audience, chain, ModeSwitch.AGGRESSIVE, whisperFeedback, villagerFeedback, relayTextForMode(ModeSwitch.AGGRESSIVE), confirmTextForMode(ModeSwitch.AGGRESSIVE));

            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean applyStopMacro(ServerPlayer sp, ChatAudience audience, boolean chain, boolean whisperFeedback, List<VillagerChatFeedback> villagerFeedback, String relayText, String confirmText) {
        try {
            if (sp == null) return false;
            java.util.HashSet<UUID> relayers = new java.util.HashSet<>();
            List<Villager> targets = collectOwnedVillagersForStopMacro(sp, audience, chain, relayers);
            if (targets.isEmpty()) return false;

            for (Villager vill : targets) {
                if (vill == null) continue;
                if (!RecruitService.isRecruited(vill)) continue;
                if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;
                if (!CustomCommandsService.isExecuting(vill)) continue;

                try { CustomCommandsService.stopExecution(vill); } catch (Throwable ignored) {}
                try { org.z2six.villageroverhaul.server.ai.VillagerSeatService.dismountIfSeated(vill, "cc_stop"); } catch (Throwable ignored) {}
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                if (relayers.contains(vill.getUUID())) {
                    queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                }
            }

            // Consider this handled even if no one was executing (it's still a valid command).
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean applyLoadoutSwap(ServerPlayer sp, ChatAudience audience, boolean chain, boolean equip, boolean whisperFeedback, List<VillagerChatFeedback> villagerFeedback, String relayText, String confirmText) {
        try {
            if (sp == null) return false;
            java.util.HashSet<UUID> relayers = new java.util.HashSet<>();
            List<Villager> targets = collectOwnedVillagersForChat(sp, audience, chain, relayers);
            if (targets.isEmpty()) return false;

            boolean any = false;
            for (Villager vill : targets) {
                if (vill == null) continue;
                if (!RecruitService.isRecruited(vill)) continue;
                if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;

                // Only allowed when villager is not neutral and not in manual farming control.
                try {
                    if (org.z2six.villageroverhaul.server.ai.VillagerBrain.isManualFarmingActive(vill)) continue;
                } catch (Throwable ignored) {}
                try {
                    if (org.z2six.villageroverhaul.server.ai.VillagerBrain.getMode(vill) == org.z2six.villageroverhaul.server.ai.VillagerBrain.Mode.NEUTRAL) continue;
                } catch (Throwable ignored) { continue; }

                try {
                    if (equip) {
                        org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService.forceEquipBegin(vill, true, "chat_equip");
                    } else {
                        org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService.forceEquipEnd(vill, "chat_stash");
                    }
                    queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                    if (relayers.contains(vill.getUUID())) {
                        queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                    }
                    any = true;
                } catch (Throwable ignored) {}
            }

            return any;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private enum ModeSwitch {
        NEUTRAL,
        IDLE,
        FOLLOW,
        PATROL,
        MANUAL_FARMING,
        FLEE,
        DEFEND,
        AGGRESSIVE
    }

    private static boolean applyModeSwitch(ServerPlayer sp, ChatAudience audience, boolean chain, ModeSwitch mode, boolean whisperFeedback, List<VillagerChatFeedback> villagerFeedback, String relayText, String confirmText) {
        try {
            if (sp == null || mode == null) return false;
            java.util.HashSet<UUID> relayers = new java.util.HashSet<>();
            List<Villager> targets = collectOwnedVillagersForChat(sp, audience, chain, relayers);
            if (targets.isEmpty()) return false;

            for (Villager vill : targets) {
                if (vill == null) continue;
                if (!RecruitService.isRecruited(vill)) continue;
                if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;

                switch (mode) {
                    case NEUTRAL -> {
                        // Any movement command should stop manual farming.
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.isManualFarmingActive(vill)) {
                            try {
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.setManualFarmingActive(vill, false);
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.clearPrevModeForManualFarming(vill);
                            } catch (Throwable ignored) {}
                        }
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.neutral(vill)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                    case IDLE -> {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.isManualFarmingActive(vill)) {
                            try {
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.setManualFarmingActive(vill, false);
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.clearPrevModeForManualFarming(vill);
                            } catch (Throwable ignored) {}
                        }
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.idle(vill)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                    case FOLLOW -> {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.isManualFarmingActive(vill)) {
                            try {
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.setManualFarmingActive(vill, false);
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.clearPrevModeForManualFarming(vill);
                            } catch (Throwable ignored) {}
                        }
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.follow(vill, sp)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                    case PATROL -> {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.isManualFarmingActive(vill)) {
                            try {
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.setManualFarmingActive(vill, false);
                                org.z2six.villageroverhaul.server.ai.VillagerBrain.clearPrevModeForManualFarming(vill);
                            } catch (Throwable ignored) {}
                        }
                        try {
                            if (org.z2six.villageroverhaul.server.ai.VillagerBrain.hasAnySavedPatrolRoutes(vill)) {
                                var routes = org.z2six.villageroverhaul.server.ai.VillagerBrain.listSavedPatrolRoutes(vill);
                                if (routes != null && !routes.isEmpty()) {
                                    if (org.z2six.villageroverhaul.server.ai.VillagerBrain.startPatrolRoute(vill, routes.get(0).id())) {
                                        queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                                        if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                                    }
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.idle(vill)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                    case MANUAL_FARMING -> {
                        if (!ServerConfig.enableFarmingModule) break;
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.isManualFarmingActive(vill)) break;

                        // Eligibility gate: manual farming requires a workstation + Farmer profession.
                        boolean ok = true;
                        try {
                            if (vill.getVillagerData() == null || vill.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.FARMER) ok = false;
                        } catch (Throwable ignored) {
                            ok = false;
                        }
                        try {
                            if (ok && sp.serverLevel() != null && org.z2six.villageroverhaul.server.FarmingSettingsService.getEffectiveWorkstation(sp.serverLevel(), vill) == null) ok = false;
                        } catch (Throwable ignored) {
                            ok = false;
                        }

                        if (!ok) break;

                        org.z2six.villageroverhaul.server.ai.VillagerBrain.ensureAttached(vill);
                        org.z2six.villageroverhaul.server.ai.VillagerBrain.rememberPrevModeForManualFarming(vill);
                        org.z2six.villageroverhaul.server.ai.VillagerBrain.setMode(vill, org.z2six.villageroverhaul.server.ai.VillagerBrain.Mode.NEUTRAL);
                        try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
                        org.z2six.villageroverhaul.server.ai.VillagerBrain.setManualFarmingActive(vill, true);
                        queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                        if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                    }
                    case FLEE -> {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.combatFlee(vill)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                    case DEFEND -> {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.combatDefend(vill)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                    case AGGRESSIVE -> {
                        if (org.z2six.villageroverhaul.server.ai.VillagerBrain.combatAggressive(vill)) {
                            queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, confirmText);
                            if (relayers.contains(vill.getUUID())) queueVillagerChatFeedback(villagerFeedback, vill, whisperFeedback, relayText);
                        }
                    }
                }
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matches(String phrase, String msg, boolean caseSensitive) {
        return ChatCommandMatcher.matches(phrase, msg, caseSensitive);
    }

    private static void onLivingIncomingDamage(LivingIncomingDamageEvent e) {
        try {
            if (e == null) return;
            LivingEntity victim = e.getEntity();
            if (!(victim instanceof Villager vill)) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            UUID ownerUuid = RecruitService.getRecruiterUuid(vill);
            if (ownerUuid == null) return;

            Player ownerAttacker = resolvePlayerAttacker(e);
            if (ownerAttacker != null && ownerUuid.equals(ownerAttacker.getUUID())) {
                cancelIncomingDamage(e);
                return;
            }

            Villager alliedVillagerAttacker = resolveVillagerAttacker(e);
            if (alliedVillagerAttacker == null) return;
            if (alliedVillagerAttacker == vill) {
                cancelIncomingDamage(e);
                return;
            }

            if (!RecruitService.isRecruited(alliedVillagerAttacker)) return;
            UUID attackerOwner = RecruitService.getRecruiterUuid(alliedVillagerAttacker);
            if (attackerOwner == null || !ownerUuid.equals(attackerOwner)) return;

            cancelIncomingDamage(e);
        } catch (Throwable ignored) {}
    }

    private static Player resolvePlayerAttacker(LivingIncomingDamageEvent e) {
        try {
            Entity sourceEntity = resolveResponsibleDamageEntity(e);
            if (sourceEntity instanceof Player player) return player;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Villager resolveVillagerAttacker(LivingIncomingDamageEvent e) {
        try {
            Entity sourceEntity = resolveResponsibleDamageEntity(e);
            if (sourceEntity instanceof Villager villager) return villager;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Entity resolveResponsibleDamageEntity(LivingIncomingDamageEvent e) {
        try {
            if (e == null || e.getSource() == null) return null;

            Entity sourceEntity = e.getSource().getEntity();
            if (sourceEntity != null) return sourceEntity;

            Entity directEntity = e.getSource().getDirectEntity();
            if (directEntity instanceof TraceableEntity traceable) {
                Entity owner = traceable.getOwner();
                if (owner != null) return owner;
            }

            return directEntity;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void cancelIncomingDamage(LivingIncomingDamageEvent e) {
        try { e.setCanceled(true); } catch (Throwable ignored) {}
        try {
            var setter = e.getClass().getMethod("setAmount", float.class);
            setter.invoke(e, 0.0f);
        } catch (Throwable ignored) {}
    }

    private static void sendOverlayText(ServerPlayer sp, String text, int durationMs) {
        try {
            if (sp == null || sp.connection == null || text == null) return;
            sp.connection.send(new ClientboundCustomPayloadPacket(new PacketFarmingOverlayText(text, durationMs)));
        } catch (Throwable ignored) {}
    }

    private static int findMatchingMacroAction(Villager vill, ServerPlayer sp, String content, boolean requireChain) {
        try {
            if (vill == null || sp == null || content == null) return -1;
            var actions = CustomCommandsService.getActionsMeta(vill);
            if (actions == null || actions.isEmpty()) return -1;
            for (int i = 0; i < actions.size(); i++) {
                var action = actions.get(i);
                if (action == null) continue;
                if (requireChain && !action.chain()) continue;
                boolean allowed = false;
                try { allowed = action.anyone() || org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp); } catch (Throwable ignored) { allowed = false; }
                if (!allowed) continue;
                if (!CustomCommandsService.matchesCommand(action, content)) continue;
                return i;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void addMacroMatch(java.util.Map<UUID, MacroMatch> matches, Villager vill, int actionIdx) {
        try {
            if (matches == null || vill == null || actionIdx < 0) return;
            UUID id = vill.getUUID();
            if (id == null || matches.containsKey(id)) return;
            matches.put(id, new MacroMatch(vill, actionIdx));
        } catch (Throwable ignored) {}
    }

    private static List<Villager> collectOwnedVillagersForChat(ServerPlayer sp, ChatAudience audience, boolean chain, java.util.Set<UUID> relayers) {
        try {
            if (sp == null || sp.serverLevel() == null) return List.of();

            UUID owner = sp.getUUID();

            java.util.ArrayList<Villager> initial = new java.util.ArrayList<>();
            for (Villager vill : collectVillagersForAudience(sp, audience)) {
                if (vill == null) continue;
                if (!RecruitService.isRecruited(vill)) continue;
                UUID r = RecruitService.getRecruiterUuid(vill);
                if (r == null || !r.equals(owner)) continue;
                if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;
                if (!CustomCommandsService.isChatListening(vill)) continue;
                initial.add(vill);
            }

            if (!chain) return initial;

            java.util.HashSet<UUID> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<Villager> q = new java.util.ArrayDeque<>();
            for (Villager v : initial) {
                if (v == null) continue;
                seen.add(v.getUUID());
                q.add(v);
            }

            java.util.ArrayList<Villager> out = new java.util.ArrayList<>(initial);
            while (!q.isEmpty() && out.size() < 256) {
                Villager cur = q.poll();
                if (cur == null) continue;

                if (!CustomCommandsService.isChatPassing(cur)) continue;
                int passRange = audienceRangeClamp(audience);

                var level = (ServerLevel) cur.level();
                boolean relayed = false;
                for (Villager vill : level.getEntitiesOfClass(Villager.class, cur.getBoundingBox().inflate(passRange))) {
                    if (vill == null) continue;
                    UUID id = vill.getUUID();
                    if (id == null || seen.contains(id)) continue;
                    if (!RecruitService.isRecruited(vill)) continue;
                    UUID r = RecruitService.getRecruiterUuid(vill);
                    if (r == null || !r.equals(owner)) continue;
                    if (!CustomCommandsService.isChatListening(vill)) continue;
                    if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;

                    seen.add(id);
                    out.add(vill);
                    q.add(vill);
                    relayed = true;
                    if (out.size() >= 256) break;
                }
                if (relayed && relayers != null) relayers.add(cur.getUUID());
            }

            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<Villager> collectOwnedVillagersForStopMacro(ServerPlayer sp, ChatAudience audience, boolean chain, java.util.Set<UUID> relayers) {
        try {
            if (sp == null || sp.serverLevel() == null) return List.of();

            UUID owner = sp.getUUID();

            java.util.ArrayList<Villager> initial = new java.util.ArrayList<>();
            for (Villager vill : collectVillagersForAudience(sp, audience)) {
                if (vill == null) continue;
                if (!RecruitService.isRecruited(vill)) continue;
                UUID r = RecruitService.getRecruiterUuid(vill);
                if (r == null || !r.equals(owner)) continue;
                if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;
                initial.add(vill);
            }

            if (!chain) return initial;

            java.util.HashSet<UUID> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<Villager> q = new java.util.ArrayDeque<>();
            for (Villager v : initial) {
                if (v == null) continue;
                seen.add(v.getUUID());
                q.add(v);
            }

            java.util.ArrayList<Villager> out = new java.util.ArrayList<>(initial);
            while (!q.isEmpty() && out.size() < 256) {
                Villager cur = q.poll();
                if (cur == null) continue;

                if (!CustomCommandsService.isChatPassing(cur)) continue;
                int passRange = audienceRangeClamp(audience);

                var level = (ServerLevel) cur.level();
                boolean relayed = false;
                for (Villager vill : level.getEntitiesOfClass(Villager.class, cur.getBoundingBox().inflate(passRange))) {
                    if (vill == null) continue;
                    UUID id = vill.getUUID();
                    if (id == null || seen.contains(id)) continue;
                    if (!RecruitService.isRecruited(vill)) continue;
                    UUID r = RecruitService.getRecruiterUuid(vill);
                    if (r == null || !r.equals(owner)) continue;
                    if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) continue;

                    seen.add(id);
                    out.add(vill);
                    q.add(vill);
                    relayed = true;
                    if (out.size() >= 256) break;
                }
                if (relayed && relayers != null) relayers.add(cur.getUUID());
            }

            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
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

    private static void queueVillagerChatFeedback(List<VillagerChatFeedback> out, Villager vill, boolean whisper, String text) {
        try {
            if (out == null || vill == null || text == null || text.isBlank()) return;
            out.add(new VillagerChatFeedback(vill, whisper, text));
        } catch (Throwable ignored) {}
    }

    private static void flushVillagerChatFeedback(List<VillagerChatFeedback> feedback) {
        try {
            if (feedback == null || feedback.isEmpty()) return;
            for (VillagerChatFeedback entry : feedback) {
                if (entry == null) continue;
                sendVillagerChatFeedback(entry.vill(), entry.whisper(), entry.text());
            }
        } catch (Throwable ignored) {}
    }

    private static void sendVillagerChatFeedback(Villager vill, boolean whisper, String text) {
        try {
            if (vill == null || vill.level().isClientSide()) return;
            if (!(vill.level() instanceof ServerLevel level)) return;

            Component body = Component.literal(toRuneHybrid(text));
            Component line = Component.translatable("chat.type.text", vill.getDisplayName(), body);
            if (whisper) {
                line = line.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            }

            if (whisper) {
                double r2 = (double) Math.max(1, ServerConfig.whisperRange) * (double) Math.max(1, ServerConfig.whisperRange);
                for (ServerPlayer other : level.players()) {
                    if (other == null || other.connection == null) continue;
                    try {
                        if (other.distanceToSqr(vill) > r2) continue;
                        other.connection.send(new ClientboundSystemChatPacket(line, false));
                    } catch (Throwable ignored) {}
                }
                return;
            }

            if (ServerConfig.localizedChatEnabled) {
                double r2 = (double) Math.max(1, ServerConfig.localizedChatRange) * (double) Math.max(1, ServerConfig.localizedChatRange);
                for (ServerPlayer other : level.players()) {
                    if (other == null || other.connection == null) continue;
                    try {
                        if (other.distanceToSqr(vill) > r2) continue;
                        other.connection.send(new ClientboundSystemChatPacket(line, false));
                    } catch (Throwable ignored) {}
                }
                return;
            }

            for (ServerPlayer other : level.players()) {
                if (other == null || other.connection == null) continue;
                try { other.connection.send(new ClientboundSystemChatPacket(line, false)); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static String relayTextForHelp() { return "Please help?"; }
    private static String confirmTextForHelp() { return "I will help."; }
    private static String relayTextForStopMacro() { return "Stop the task?"; }
    private static String confirmTextForStopMacro() { return "I will stop."; }
    private static String relayTextForLoadout(boolean equip) { return equip ? "Arm yourselves?" : "Stow your gear?"; }
    private static String confirmTextForLoadout(boolean equip) { return equip ? "I am armed." : "I will stash my gear."; }
    private static String relayTextForMacro() { return "Did you hear that?"; }
    private static String confirmTextForMacro() { return "I understand."; }

    private static String relayTextForMode(ModeSwitch mode) {
        return switch (mode) {
            case NEUTRAL -> "Stand by?";
            case IDLE -> "Hold position?";
            case FOLLOW -> "Follow along?";
            case PATROL -> "Begin patrol?";
            case MANUAL_FARMING -> "Work the fields?";
            case FLEE -> "Fall back?";
            case DEFEND -> "Defend us?";
            case AGGRESSIVE -> "Attack on sight?";
        };
    }

    private static String confirmTextForMode(ModeSwitch mode) {
        return switch (mode) {
            case NEUTRAL -> "I will stand by.";
            case IDLE -> "I will stay here.";
            case FOLLOW -> "I will follow.";
            case PATROL -> "I will patrol.";
            case MANUAL_FARMING -> "I will tend the fields.";
            case FLEE -> "I will fall back.";
            case DEFEND -> "I will defend.";
            case AGGRESSIVE -> "I will attack.";
        };
    }

    private static String toRuneHybrid(String text) {
        try {
            if (text == null || text.isEmpty()) return "";
            StringBuilder out = new StringBuilder(text.length() * 2);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                out.append(switch (Character.toUpperCase(c)) {
                    case 'A' -> "ᚨ";
                    case 'B' -> "ᛒ";
                    case 'C' -> "ᚲ";
                    case 'D' -> "ᛞ";
                    case 'E' -> "ᛖ";
                    case 'F' -> "ᚠ";
                    case 'G' -> "ᚷ";
                    case 'H' -> "ᚺ";
                    case 'I' -> "ᛁ";
                    case 'J' -> "ᛃ";
                    case 'K' -> "ᚴ";
                    case 'L' -> "ᛚ";
                    case 'M' -> "ᛗ";
                    case 'N' -> "ᚾ";
                    case 'O' -> "ᛟ";
                    case 'P' -> "ᛈ";
                    case 'Q' -> "Ϙ";
                    case 'R' -> "ᚱ";
                    case 'S' -> "ᛊ";
                    case 'T' -> "ᛏ";
                    case 'U' -> "ᚢ";
                    case 'V' -> "ᚡ";
                    case 'W' -> "ᚹ";
                    case 'X' -> "ᛪ";
                    case 'Y' -> "ᛦ";
                    case 'Z' -> "ᛉ";
                    default -> String.valueOf(c);
                });
            }
            return out.toString();
        } catch (Throwable ignored) {
            return text == null ? "" : text;
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

            int genderId = (merchant instanceof Villager villager)
                    ? VillagerGenderService.ensureAssigned(villager)
                    : VillagerGenderService.GENDER_UNKNOWN;
            VillagerAgeService.AgeDisplay age = VillagerAgeService.getDisplay(merchant);

            sp.connection.send(new ClientboundCustomPayloadPacket(
                    new PacketVillagerStatsData(id, true, g, t, i, h, vit, agi, str, arm, mot, eff, pw, rng, genderId,
                            age.birthDateText(), age.ageText())
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
                HelpChatCommandService.tick(server);
            } catch (Throwable ignored) {}

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
