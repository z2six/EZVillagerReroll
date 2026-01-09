// ServerEvents.java
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/ServerEvents.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.PacketVillagerStatsData;

public final class ServerEvents {

    private static volatile boolean registered = false;
    private static volatile long lastTickDebugGameTime = -1;

    private ServerEvents() {}

    public static void register(IEventBus bus) {
        if (bus == null) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.register called with null bus");
            return;
        }
        if (registered) {
            EZVillagerReroll.LOG().warn("[EZVR] ServerEvents.register called twice; ignoring.");
            return;
        }
        registered = true;

        try {
            bus.addListener(ServerEvents::onServerTickPost);
            bus.addListener(ServerEvents::onServerStarted);
            bus.addListener(ServerEvents::onServerStopping);
            bus.addListener(ServerEvents::onContainerOpen);

            // NEW: villager/merchant stat initialization (EntityJoinLevelEvent)
            // Safe even when running on client because the handler exits if level.isClientSide().
            VillagerStatsEvents.register(bus);

            EZVillagerReroll.LOG().info("[EZVR] ServerEvents registered on gameplay bus.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.register failed", t);
        }
    }

    private static void onContainerOpen(PlayerContainerEvent.Open e) {
        try {
            if (e == null) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof AbstractVillager merchant)) return;

            // ---- NEW: push stats snapshot to the client when they open the merchant ----
            trySendVillagerStatsSnapshot(sp, merchant);
            // --------------------------------------------------------------------------

            if (!(merchant instanceof Villager vill)) return;

            // NEW: If awaiting payment settlement, do NOT restore/capture canonical offers here.
            if (SearchService.isAwaitingPayment(vill)) {
                EZVillagerReroll.LOG().debug("[EZVR] onContainerOpen: villager awaiting payment; skipping offer persistence (villager={} player={})",
                        vill.getUUID(), sp.getGameProfile().getName());
                return;
            }

            var level = sp.serverLevel();
            if (level == null) return;

            VillagerOffersSavedData data = VillagerOffersSavedData.get(level);
            if (data == null) {
                EZVillagerReroll.LOG().warn("[EZVR] onContainerOpen: offers saved data unavailable; skipping (player={})", sp.getGameProfile().getName());
                return;
            }

            boolean had = data.has(vill.getUUID());

            if (had) {
                boolean applied = data.apply(vill);
                if (applied) {
                    VillagerOffersSavedData.syncOffersToPlayerIfPossible(sp, menu, vill);

                    EZVillagerReroll.LOG().info(
                            "[EZVR] onContainerOpen: restored canonical offers for villager={} (player={}, offers={})",
                            vill.getUUID(),
                            sp.getGameProfile().getName(),
                            (vill.getOffers() == null ? -1 : vill.getOffers().size())
                    );
                } else {
                    EZVillagerReroll.LOG().debug(
                            "[EZVR] onContainerOpen: had entry but apply failed; leaving vanilla state (villager={}, player={})",
                            vill.getUUID(),
                            sp.getGameProfile().getName()
                    );
                }
            } else {
                data.capture(vill);

                EZVillagerReroll.LOG().info(
                        "[EZVR] onContainerOpen: captured initial offers as canonical for villager={} (player={}, offers={})",
                        vill.getUUID(),
                        sp.getGameProfile().getName(),
                        (vill.getOffers() == null ? -1 : vill.getOffers().size())
                );
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onContainerOpen failed", t);
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
            int a = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_AMBITIOUS));

            sp.connection.send(new ClientboundCustomPayloadPacket(new PacketVillagerStatsData(id, true, g, t, i, h, a)));

            EZVillagerReroll.LOG().debug("[EZVR] Sent villager stats snapshot to {} for entityId={} uuid={}",
                    sp.getGameProfile().getName(), id, merchant.getUUID());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] trySendVillagerStatsSnapshot failed (soft): {}", t.toString());
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
                EZVillagerReroll.LOG().error("[EZVR] ServerEvents: SearchService.tick failed", t);
            }

            try {
                long gt = server.overworld().getGameTime();
                if (gt % 200L == 0L && gt != lastTickDebugGameTime) {
                    lastTickDebugGameTime = gt;
                    EZVillagerReroll.LOG().debug("[EZVR] Server tick heartbeat (gameTime={})", gt);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.onServerTickPost failed", t);
        }
    }

    private static void onServerStarted(ServerStartedEvent e) {
        try {
            if (e == null) return;
            MinecraftServer server = e.getServer();
            if (server == null) return;

            EZVillagerReroll.LOG().info("[EZVR] ServerStarted: loading persisted auto-search tasks.");
            try {
                SearchSavedData.loadIntoSearchService(server);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ServerStarted: loadIntoSearchService failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.onServerStarted failed", t);
        }
    }

    private static void onServerStopping(ServerStoppingEvent e) {
        try {
            if (e == null) return;
            MinecraftServer server = e.getServer();
            if (server == null) return;

            EZVillagerReroll.LOG().info("[EZVR] ServerStopping: saving persisted auto-search tasks.");
            try {
                SearchSavedData.saveFromSearchService(server);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ServerStopping: saveFromSearchService failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.onServerStopping failed", t);
        }
    }
}
