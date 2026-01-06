// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/Network.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class Network {

    private Network() {}

    public static void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent e) {
        try {
            var r = e.registrar("ezvillagerreroll");

            r.playToServer(PacketTooltipQuery.TYPE, PacketTooltipQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTooltipQueryServer(msg, ctx));
            r.playToServer(PacketRequestReroll.TYPE, PacketRequestReroll.STREAM_CODEC,
                    (msg, ctx) -> handleRerollServer(msg, ctx));

            r.playToServer(PacketTradeLocksQuery.TYPE, PacketTradeLocksQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTradeLocksQueryServer(msg, ctx));
            r.playToServer(PacketToggleTradeLock.TYPE, PacketToggleTradeLock.STREAM_CODEC,
                    (msg, ctx) -> handleToggleTradeLockServer(msg, ctx));

            r.playToClient(PacketTooltipData.TYPE, PacketTooltipData.STREAM_CODEC,
                    (msg, ctx) -> handleTooltipDataClient(msg, ctx));
            r.playToClient(PacketSyncConfig.TYPE, PacketSyncConfig.STREAM_CODEC,
                    (msg, ctx) -> handleSyncConfigClient(msg, ctx));

            r.playToClient(PacketTradeLocks.TYPE, PacketTradeLocks.STREAM_CODEC,
                    (msg, ctx) -> handleTradeLocksClient(msg, ctx));

            EZVillagerReroll.LOG().info("[EZVR] Network payloads registered.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Network payload registration failed.", t);
        }
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
    public static void sendToServer(PacketTradeLocksQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketToggleTradeLock msg) { sendToServer((CustomPacketPayload) msg); }

    private static void handleTooltipQueryServer(PacketTooltipQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                var player = ctx.player();
                if (!(player instanceof ServerPlayer sp)) return;

                var data = org.z2six.ezvillagerreroll.server.TooltipService
                        .computeSnapshot(sp, msg.traderEntityId());

                ctx.reply(data);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TooltipQuery handler error", t);
            }
        });
    }

    private static void handleTradeLocksQueryServer(PacketTradeLocksQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                var player = ctx.player();
                if (!(player instanceof ServerPlayer sp)) return;

                var data = org.z2six.ezvillagerreroll.server.TradeLockService.computeSnapshot(sp, msg.traderEntityId());
                ctx.reply(data);

            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TradeLocksQuery handler error", t);
            }
        });
    }

    private static void handleToggleTradeLockServer(PacketToggleTradeLock msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                org.z2six.ezvillagerreroll.network.ServerHandlers.handleToggleTradeLock(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ToggleTradeLock handler error", t);
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

    private static void handleTradeLocksClient(PacketTradeLocks msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                // INFO so you can see it without enabling debug logging
                EZVillagerReroll.LOG().info("[EZVR] Client received PacketTradeLocks (class={})", msg == null ? "null" : msg.getClass().getName());
                ClientTradeLockCache.set(msg);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TradeLocks client handler error", t);
            }
        });
    }

    private static void handleSyncConfigClient(PacketSyncConfig msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ClientSyncedConfig.applyFromServer(msg);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SyncConfig client handler error", t);
            }
        });
    }
}
