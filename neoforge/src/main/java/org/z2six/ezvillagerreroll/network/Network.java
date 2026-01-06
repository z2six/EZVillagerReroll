// MainFile: src/main/java/org/z2six/ezvillagerreroll/network/Network.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

@EventBusSubscriber(modid = EZVillagerReroll.MODID)
public final class Network {

    private Network() {}

    public static void init() {
        // Intentionally empty. Payload registration is handled by the RegisterPayloadHandlersEvent listener.
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent e) {
        var r = e.registrar(EZVillagerReroll.MODID);

        // Client -> Server
        r.playToServer(PacketTooltipQuery.TYPE, PacketTooltipQuery.STREAM_CODEC,
                (msg, ctx) -> handleTooltipQueryServer(msg, ctx));
        r.playToServer(PacketRequestReroll.TYPE, PacketRequestReroll.STREAM_CODEC,
                (msg, ctx) -> handleRerollServer(msg, ctx));

        // Server -> Client
        r.playToClient(PacketTooltipData.TYPE, PacketTooltipData.STREAM_CODEC,
                (msg, ctx) -> handleTooltipDataClient(msg, ctx));
        r.playToClient(PacketSyncConfig.TYPE, PacketSyncConfig.STREAM_CODEC,
                (msg, ctx) -> handleSyncConfigClient(msg, ctx));

        EZVillagerReroll.LOG().info("[EZVR] Network payloads registered.");
    }

    public static void sendToServer(CustomPacketPayload payload) {
        try {
            var mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) {
                EZVillagerReroll.LOG().warn("[EZVR] sendToServer: no client connection; dropping {}", payload.type());
                return;
            }
            mc.getConnection().send(new ServerboundCustomPayloadPacket(payload));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] sendToServer failed for {}", payload.getClass().getName(), t);
        }
    }

    public static void sendToServer(PacketRequestReroll msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTooltipQuery msg)  { sendToServer((CustomPacketPayload) msg); }

    private static void handleTooltipQueryServer(PacketTooltipQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                var player = ctx.player();
                if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                PacketTooltipData data = org.z2six.ezvillagerreroll.server.TooltipService.computeSnapshot(sp, msg.traderEntityId());
                ctx.reply(data);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TooltipQuery handler error", t);
            }
        });
    }

    private static void handleRerollServer(PacketRequestReroll msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleReroll(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] Reroll handler error", t);
            }
        });
    }

    private static void handleTooltipDataClient(PacketTooltipData msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ClientTooltipCache.set(msg);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TooltipData client handler error", t);
            }
        });
    }

    private static void handleSyncConfigClient(PacketSyncConfig msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ClientSyncedConfig.setFrom(msg);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SyncConfig client handler error", t);
            }
        });
    }
}
