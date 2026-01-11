// ServerEvents.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ServerEvents.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.ServerSync;

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

            // NEW: sync server config to players when they log in (fixes client tooltip mapping)
            bus.addListener(ServerEvents::onPlayerLoggedIn);

            // NEW: villager/merchant stat initialization (EntityJoinLevelEvent)
            // Safe even when running on client because the handler exits if level.isClientSide().
            VillagerStatsEvents.register(bus);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] ServerEvents registered on gameplay bus.");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerEvents.register failed", t);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        try {
            if (e == null) return;
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;

            // Make sure client has the synced server config early (tooltip mapping relies on this).
            ServerSync.syncTo(sp);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onPlayerLoggedIn: synced config to {}", sp.getGameProfile().getName());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] onPlayerLoggedIn failed (soft): {}", t.toString());
        }
    }

    private static void onContainerOpen(PlayerContainerEvent.Open e) {
        try {
            if (e == null) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            // Extra safety: ensure config is synced by the time merchant UI opens.
            // This prevents races in SP where the UI opens immediately after login.
            try { ServerSync.syncTo(sp); } catch (Throwable ignored) {}

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof AbstractVillager merchant)) return;

            // ---- NEW: push stats snapshot to the client when they open the merchant ----
            trySendVillagerStatsSnapshot(sp, merchant);
            // --------------------------------------------------------------------------

            if (!(merchant instanceof Villager vill)) return;

            // NEW: If awaiting payment settlement, do NOT restore/capture canonical offers here.
            if (SearchService.isAwaitingPayment(vill)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] onContainerOpen: villager awaiting payment; skipping offer persistence (villager={} player={})",
                        vill.getUUID(), sp.getGameProfile().getName());
                return;
            }

            var level = sp.serverLevel();
            if (level == null) return;

            VillagerOffersSavedData data = VillagerOffersSavedData.get(level);
            if (data == null) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] onContainerOpen: offers saved data unavailable; skipping (player={})", sp.getGameProfile().getName());
                return;
            }

            boolean had = data.has(vill.getUUID());

            if (had) {
                boolean applied = data.apply(vill);
                if (applied) {
                    VillagerOffersSavedData.syncOffersToPlayerIfPossible(sp, menu, vill);

                    VillagerOverhaul.LOG().info(
                            "[VillagerOverhaul] onContainerOpen: restored canonical offers for villager={} (player={}, offers={})",
                            vill.getUUID(),
                            sp.getGameProfile().getName(),
                            (vill.getOffers() == null ? -1 : vill.getOffers().size())
                    );
                } else {
                    VillagerOverhaul.LOG().debug(
                            "[VillagerOverhaul] onContainerOpen: had entry but apply failed; leaving vanilla state (villager={}, player={})",
                            vill.getUUID(),
                            sp.getGameProfile().getName()
                    );
                }
            } else {
                data.capture(vill);

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] onContainerOpen: captured initial offers as canonical for villager={} (player={}, offers={})",
                        vill.getUUID(),
                        sp.getGameProfile().getName(),
                        (vill.getOffers() == null ? -1 : vill.getOffers().size())
                );
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onContainerOpen failed", t);
        }
    }

    private static void trySendVillagerStatsSnapshot(ServerPlayer sp, AbstractVillager merchant) {
        try {
            if (sp == null || sp.connection == null) return;
            if (merchant == null) return;

            // Ensure stats exist on server (no-op if already assigned)
            VillagerStatsService.ensureStats(merchant);

            int id = merchant.getId();

            CompoundTag pd = merchant.getPersistentData();
            if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                sp.connection.send(new ClientboundCustomPayloadPacket(PacketVillagerStatsData.missing(id)));
                return;
            }

            CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);

            int g = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
            int t = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
            int i = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
            int h = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));

            sp.connection.send(new ClientboundCustomPayloadPacket(new PacketVillagerStatsData(id, true, g, t, i, h)));

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
}
