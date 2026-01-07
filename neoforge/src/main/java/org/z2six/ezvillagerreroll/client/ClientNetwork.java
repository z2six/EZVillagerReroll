// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/ClientNetwork.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * CLIENT-ONLY helper for sending custom payloads to the server.
 *
 * Kept separate so common code can reflect into it without loading client classes on a dedicated server.
 */
public final class ClientNetwork {

    private ClientNetwork() {}

    public static void sendToServer(CustomPacketPayload payload) {
        try {
            if (payload == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                EZVillagerReroll.LOG().warn("[EZVR] ClientNetwork.sendToServer: no client connection; dropping {}", payload.type());
                return;
            }

            mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientNetwork.sendToServer failed for {}", payload == null ? "null" : payload.getClass().getName(), t);
        }
    }
}
