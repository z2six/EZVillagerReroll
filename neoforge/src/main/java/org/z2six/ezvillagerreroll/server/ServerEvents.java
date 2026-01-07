// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/ServerEvents.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * Gameplay/runtime event wiring (NeoForge.EVENT_BUS).
 *
 * Responsibilities:
 * - Drive SearchService tick loop.
 * - Load/Save persisted auto-search tasks on server start/stop (SearchSavedData).
 *
 * NOTE:
 * BusyVillagerBlocker is intentionally NOT registered here anymore;
 * it is wired explicitly in the main mod class (EZVillagerReroll) as requested.
 */
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

            EZVillagerReroll.LOG().info("[EZVR] ServerEvents registered on gameplay bus.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerEvents.register failed", t);
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
