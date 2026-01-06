// MainFile: src/main/java/org/z2six/ezvillagerreroll/server/ServerEvents.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.network.PacketSyncConfig;

@EventBusSubscriber(modid = EZVillagerReroll.MODID)
public final class ServerEvents {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent e) {
        try {
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;

            PacketSyncConfig snap = ServerConfig.snapshotForSync();
            // Reply-style sending isn't available here; use direct connection send via vanilla packets not exposed.
            // NeoForge provides ctx.reply in handlers, but for login event we rely on the built-in "send" helper:
            // We'll use sp.connection.send with ClientboundCustomPayloadPacket.
            // This is safe; if it fails, we just log.
            try {
                sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(snap));
                EZVillagerReroll.LOG().info("[EZVR] Sent config sync to player={} (v{}, hash={})",
                        sp.getGameProfile().getName(), snap.version, snap.hash);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] Failed to send config sync to {}: {}", sp.getGameProfile().getName(), t.toString());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onPlayerLogin error", t);
        }
    }

    private ServerEvents() {}
}
