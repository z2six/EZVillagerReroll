// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/ServerEvents.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ServerSync;

public final class ServerEvents {

    private ServerEvents() {}

    public static void register(IEventBus neoForgeBus) {
        try {
            neoForgeBus.addListener(ServerEvents::onPlayerLoggedIn);
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
}
