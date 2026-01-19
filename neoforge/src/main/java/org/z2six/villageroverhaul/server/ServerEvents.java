// neoforge\src\main\java\org\z2six\villageroverhaul\server\ServerEvents.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.recruit.PacketOpenRecruitScreen;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.ServerSync;
import org.z2six.villageroverhaul.logic.HoarderOffers;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;
import org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService;
import org.z2six.villageroverhaul.server.ai.VillagerEatTestService;

public final class ServerEvents {

    private static volatile boolean registered = false;
    private static volatile long lastTickDebugGameTime = -1;

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

            // villager/merchant stat initialization
            VillagerStatsEvents.register(bus);

            // Villager brain/module attach (AI goals)
            bus.addListener(VillagerBrain::onEntityJoinLevel);
            
            // Villager's on spawn event for CombatInventory
            VillagerCombatInventoryProbeEvents.register(bus);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] ServerEvents registered on gameplay bus.");
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

            sp.connection.send(new ClientboundCustomPayloadPacket(
                    new PacketVillagerStatsData(id, true, g, t, i, h, vit, agi, str, arm)
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
                VillagerCombatLoadoutService.tick(server);
            } catch (Throwable ignored) {}

            try {
                VillagerEatTestService.tick(server);
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

            VillagerOverhaul.LOG().info("[VillagerOverhaul] ServerStarted: loading persisted auto-search tasks.");
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

            VillagerOverhaul.LOG().info("[VillagerOverhaul] ServerStopping: saving persisted auto-search tasks.");
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
