// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/Network.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.server.TradeLockService;

/**
 * COMMON/DEDICATED-SERVER SAFE network registration + send helpers.
 *
 * IMPORTANT:
 * - Payload IDs must be registered on BOTH sides (client and server) for NeoForge handshake.
 * - This class MUST NOT reference any net.minecraft.client.* types.
 * - Client-only behavior is delegated via reflection.
 */
public final class Network {

    private static final String CLIENT_HANDLERS_CLASS = "org.z2six.ezvillagerreroll.client.ClientNetworkHandlers";
    private static final String CLIENT_NETWORK_CLASS  = "org.z2six.ezvillagerreroll.client.ClientNetwork";

    private Network() {}

    // ============================================================
    // Registration
    // ============================================================

    public static void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent e) {
        try {
            var r = e.registrar("ezvillagerreroll");

            // -------------------------
            // Serverbound packets
            // (must be registered on both sides; received on server)
            // -------------------------
            r.playToServer(PacketTooltipQuery.TYPE, PacketTooltipQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTooltipQueryServer(msg, ctx));
            r.playToServer(PacketRequestReroll.TYPE, PacketRequestReroll.STREAM_CODEC,
                    (msg, ctx) -> handleRerollServer(msg, ctx));

            // Trade lock feature
            r.playToServer(PacketTradeLocksQuery.TYPE, PacketTradeLocksQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTradeLocksQueryServer(msg, ctx));
            r.playToServer(PacketToggleTradeLock.TYPE, PacketToggleTradeLock.STREAM_CODEC,
                    (msg, ctx) -> handleToggleTradeLockServer(msg, ctx));

            // Auto-search feature (serverbound)
            r.playToServer(PacketSearchCatalogQuery.TYPE, PacketSearchCatalogQuery.STREAM_CODEC,
                    (msg, ctx) -> handleSearchCatalogQueryServer(msg, ctx));
            r.playToServer(PacketStartAutoSearch.TYPE, PacketStartAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleStartAutoSearchServer(msg, ctx));
            r.playToServer(PacketCancelAutoSearch.TYPE, PacketCancelAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleCancelAutoSearchServer(msg, ctx));
            r.playToServer(PacketContinueAutoSearch.TYPE, PacketContinueAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleContinueAutoSearchServer(msg, ctx));

            // -------------------------
            // Clientbound packets
            // CRITICAL: must ALSO be registered on dedicated server for handshake!
            // Handler will only run on client (since server won't receive these),
            // but the registration must exist on both sides.
            // -------------------------
            r.playToClient(PacketTooltipData.TYPE, PacketTooltipData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTooltipData", msg, ctx));
            r.playToClient(PacketSyncConfig.TYPE, PacketSyncConfig.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSyncConfig", msg, ctx));
            r.playToClient(PacketTradeLocks.TYPE, PacketTradeLocks.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTradeLocks", msg, ctx));

            // Auto-search clientbound
            r.playToClient(PacketSearchCatalogData.TYPE, PacketSearchCatalogData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSearchCatalogData", msg, ctx));
            r.playToClient(PacketOpenBusyScreen.TYPE, PacketOpenBusyScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenBusyScreen", msg, ctx));

            EZVillagerReroll.LOG().info("[EZVR] Network payloads registered (handshake-safe). distClient={}", isClientDist());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Network payload registration failed.", t);
        }
    }

    /**
     * Dispatches to client-only handler methods via reflection.
     *
     * On dedicated server:
     * - this will never be invoked in practice (no clientbound packets received),
     *   but if it is, it safely no-ops.
     */
    private static void dispatchToClientHandler(String methodName, Object msg, IPayloadContext ctx) {
        try {
            if (!isClientDist()) {
                // Shouldn't happen in normal flow; keep it soft.
                EZVillagerReroll.LOG().debug("[EZVR] dispatchToClientHandler({}) called on non-client dist; ignoring.", methodName);
                return;
            }

            Class<?> c = Class.forName(CLIENT_HANDLERS_CLASS);
            // signature: (Object msg, IPayloadContext ctx) OR (ConcreteMsg, IPayloadContext)
            // We'll try exact concrete signature first; if it fails, fall back to (Object, IPayloadContext).
            try {
                c.getMethod(methodName, msg.getClass(), IPayloadContext.class).invoke(null, msg, ctx);
            } catch (NoSuchMethodException ex) {
                c.getMethod(methodName, Object.class, IPayloadContext.class).invoke(null, msg, ctx);
            }
        } catch (ClassNotFoundException cnf) {
            // If this happens on client, the mod is broken; log loudly.
            EZVillagerReroll.LOG().error("[EZVR] Missing client handler class {} (cannot handle {}).", CLIENT_HANDLERS_CLASS, methodName);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client handler dispatch failed for {}", methodName, t);
        }
    }

    private static boolean isClientDist() {
        try {
            Class<?> env = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = env.getField("dist").get(null);
            return (boolean) dist.getClass().getMethod("isClient").invoke(dist);
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================
    // Client -> Server send helpers (server-safe via reflection)
    // ============================================================

    /**
     * Sends a payload to server on the client.
     *
     * Dedicated server safety:
     * - Never references Minecraft client classes.
     * - Delegates to ClientNetwork via reflection only on client dist.
     */
    public static void sendToServer(CustomPacketPayload payload) {
        try {
            if (payload == null) return;

            if (!isClientDist()) {
                // Common code might call this defensively; just drop on server.
                EZVillagerReroll.LOG().debug("[EZVR] Network.sendToServer called on non-client dist; dropping {}", payload.type());
                return;
            }

            Class<?> c = Class.forName(CLIENT_NETWORK_CLASS);
            c.getMethod("sendToServer", CustomPacketPayload.class).invoke(null, payload);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Network.sendToServer failed for {}", payload == null ? "null" : payload.getClass().getName(), t);
        }
    }

    // Convenience overloads
    public static void sendToServer(PacketRequestReroll msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTooltipQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTradeLocksQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketToggleTradeLock msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketSearchCatalogQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketStartAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketCancelAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketContinueAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }

    // ============================================================
    // Server-side packet handlers
    // ============================================================

    private static void handleTooltipQueryServer(PacketTooltipQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                var player = ctx.player();
                if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                var data = org.z2six.ezvillagerreroll.server.TooltipService.computeSnapshot(sp, msg.traderEntityId());
                ctx.reply(data);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TooltipQuery handler error", t);
            }
        });
    }

    private static void handleTradeLocksQueryServer(PacketTradeLocksQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                ctx.reply(TradeLockService.computeSnapshot(sp));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] TradeLocksQuery handler error", t);
            }
        });
    }

    private static void handleToggleTradeLockServer(PacketToggleTradeLock msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleToggleTradeLock(msg, ctx);
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

    private static void handleSearchCatalogQueryServer(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleSearchCatalogQuery(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchCatalogQuery handler error", t);
            }
        });
    }

    private static void handleStartAutoSearchServer(PacketStartAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleStartAutoSearch(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] StartAutoSearch handler error", t);
            }
        });
    }

    private static void handleCancelAutoSearchServer(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleCancelAutoSearch(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] CancelAutoSearch handler error", t);
            }
        });
    }

    private static void handleContinueAutoSearchServer(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleContinueAutoSearch(msg, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ContinueAutoSearch handler error", t);
            }
        });
    }
}
