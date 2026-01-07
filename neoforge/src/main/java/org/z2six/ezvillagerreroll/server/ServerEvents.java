// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/ServerEvents.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ServerSync;

/**
 * Runtime (gameplay) server event listeners.
 *
 * IMPORTANT:
 * - Do NOT use EventBusSubscriber (deprecated in your setup).
 * - Register from EZVillagerReroll main mod class via ServerEvents.register(NeoForge.EVENT_BUS).
 */
public final class ServerEvents {

    private ServerEvents() {}

    public static void register(IEventBus neoForgeBus) {
        try {
            neoForgeBus.addListener(ServerEvents::onPlayerLoggedIn);
            neoForgeBus.addListener(ServerEvents::onServerTickPost);

            // BusyVillagerBlocker is gameplay too; if you already registered it elsewhere, keep only one registration.
            neoForgeBus.addListener(BusyVillagerBlocker::onEntityInteract);

            EZVillagerReroll.LOG().info("[EZVR] ServerEvents registered on NeoForge bus.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents registration failed.", t);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        try {
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            EZVillagerReroll.LOG().debug("[EZVR] Player logged in -> syncing server config (player={})", sp.getGameProfile().getName());
            ServerSync.syncTo(sp);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onPlayerLoggedIn failed.", t);
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post e) {
        try {
            MinecraftServer server = e.getServer();
            if (server == null) return;

            // This is the real auto-search tick driver (replaces the missing AutoSearchService).
            SearchService.tick(server);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onServerTickPost failed.", t);
        }
    }
}
