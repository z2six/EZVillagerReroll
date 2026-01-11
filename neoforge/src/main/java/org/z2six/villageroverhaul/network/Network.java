// Network.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/Network.java
package org.z2six.villageroverhaul.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.TradeLockService;
import org.z2six.villageroverhaul.server.VillagerStatsService;

public final class Network {

    private static final String CLIENT_HANDLERS_CLASS = "org.z2six.villageroverhaul.client.ClientNetworkHandlers";
    private static final String CLIENT_NETWORK_CLASS  = "org.z2six.villageroverhaul.client.ClientNetwork";

    private Network() {}

    public static void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent e) {
        try {
            var r = e.registrar("villageroverhaul");

            // ---- Serverbound ----
            r.playToServer(PacketTooltipQuery.TYPE, PacketTooltipQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTooltipQueryServer(msg, ctx));
            r.playToServer(PacketRequestReroll.TYPE, PacketRequestReroll.STREAM_CODEC,
                    (msg, ctx) -> handleRerollServer(msg, ctx));

            r.playToServer(PacketTradeLocksQuery.TYPE, PacketTradeLocksQuery.STREAM_CODEC,
                    (msg, ctx) -> handleTradeLocksQueryServer(msg, ctx));
            r.playToServer(PacketToggleTradeLock.TYPE, PacketToggleTradeLock.STREAM_CODEC,
                    (msg, ctx) -> handleToggleTradeLockServer(msg, ctx));

            r.playToServer(PacketSearchCatalogQuery.TYPE, PacketSearchCatalogQuery.STREAM_CODEC,
                    (msg, ctx) -> handleSearchCatalogQueryServer(msg, ctx));
            r.playToServer(PacketStartAutoSearch.TYPE, PacketStartAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleStartAutoSearchServer(msg, ctx));
            r.playToServer(PacketCancelAutoSearch.TYPE, PacketCancelAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleCancelAutoSearchServer(msg, ctx));
            r.playToServer(PacketContinueAutoSearch.TYPE, PacketContinueAutoSearch.STREAM_CODEC,
                    (msg, ctx) -> handleContinueAutoSearchServer(msg, ctx));

            // NEW: cooldown state query
            r.playToServer(PacketRerollCooldownQuery.TYPE, PacketRerollCooldownQuery.STREAM_CODEC,
                    (msg, ctx) -> handleRerollCooldownQueryServer(msg, ctx));

            // NEW: settlement payment actions (these were missing)
            r.playToServer(PacketPayAutoSearchSettlement.TYPE, PacketPayAutoSearchSettlement.STREAM_CODEC,
                    (msg, ctx) -> handlePayAutoSearchSettlementServer(msg, ctx));
            r.playToServer(PacketDeclineAutoSearchSettlement.TYPE, PacketDeclineAutoSearchSettlement.STREAM_CODEC,
                    (msg, ctx) -> handleDeclineAutoSearchSettlementServer(msg, ctx));

            // NEW: villager stats query
            r.playToServer(PacketVillagerStatsQuery.TYPE, PacketVillagerStatsQuery.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerStatsQueryServer(msg, ctx));

            // ---- Clientbound (must be registered on BOTH sides for handshake) ----
            r.playToClient(PacketTooltipData.TYPE, PacketTooltipData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTooltipData", msg, ctx));
            r.playToClient(PacketSyncConfig.TYPE, PacketSyncConfig.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSyncConfig", msg, ctx));
            r.playToClient(PacketTradeLocks.TYPE, PacketTradeLocks.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTradeLocks", msg, ctx));

            r.playToClient(PacketSearchCatalogData.TYPE, PacketSearchCatalogData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSearchCatalogData", msg, ctx));
            r.playToClient(PacketOpenBusyScreen.TYPE, PacketOpenBusyScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenBusyScreen", msg, ctx));

            // NEW: auto-search completion notification
            r.playToClient(PacketAutoSearchDone.TYPE, PacketAutoSearchDone.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchDone", msg, ctx));

            // NEW: cooldown state update
            r.playToClient(PacketRerollCooldownState.TYPE, PacketRerollCooldownState.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRerollCooldownState", msg, ctx));

            // NEW: settlement/payment UI packets (these were missing)
            r.playToClient(PacketOpenAutoSearchPaymentScreen.TYPE, PacketOpenAutoSearchPaymentScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenAutoSearchPaymentScreen", msg, ctx));
            r.playToClient(PacketAutoSearchSettlementCleared.TYPE, PacketAutoSearchSettlementCleared.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchSettlementCleared", msg, ctx));

            // NEW: villager stats data (we update cache directly; no client-only class refs)
            r.playToClient(PacketVillagerStatsData.TYPE, PacketVillagerStatsData.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerStatsDataClient(msg, ctx));

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Network payloads registered (handshake-safe). distClient={}", isClientDist());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Network payload registration failed.", t);
        }
    }

    private static void dispatchToClientHandler(String methodName, Object msg, IPayloadContext ctx) {
        try {
            if (!isClientDist()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] dispatchToClientHandler({}) called on non-client dist; ignoring.", methodName);
                return;
            }

            Class<?> c = Class.forName(CLIENT_HANDLERS_CLASS);
            try {
                c.getMethod(methodName, msg.getClass(), IPayloadContext.class).invoke(null, msg, ctx);
            } catch (NoSuchMethodException ex) {
                c.getMethod(methodName, Object.class, IPayloadContext.class).invoke(null, msg, ctx);
            }
        } catch (ClassNotFoundException cnf) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Missing client handler class {} (cannot handle {}).", CLIENT_HANDLERS_CLASS, methodName);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client handler dispatch failed for {}", methodName, t);
        }
    }

    private static void handleVillagerStatsDataClient(PacketVillagerStatsData msg, IPayloadContext ctx) {
        try {
            // This handler is safe on both sides; actual execution occurs on client connection.
            ctx.enqueueWork(() -> {
                try {
                    ClientVillagerStatsCache.accept(msg);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerStatsDataClient failed (soft): {}", t.toString());
                }
            });
        } catch (Throwable t) {
            // soft
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

    public static void sendToServer(CustomPacketPayload payload) {
        try {
            if (payload == null) return;

            if (!isClientDist()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Network.sendToServer called on non-client dist; dropping {}", payload.type());
                return;
            }

            Class<?> c = Class.forName(CLIENT_NETWORK_CLASS);
            c.getMethod("sendToServer", CustomPacketPayload.class).invoke(null, payload);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Network.sendToServer failed for {}", payload == null ? "null" : payload.getClass().getName(), t);
        }
    }

    public static void sendToServer(PacketRequestReroll msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTooltipQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketTradeLocksQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketToggleTradeLock msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketSearchCatalogQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketStartAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketCancelAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketContinueAutoSearch msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketRerollCooldownQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketVillagerStatsQuery msg) { sendToServer((CustomPacketPayload) msg); }

    // Optional convenience (not required, but consistent):
    public static void sendToServer(PacketPayAutoSearchSettlement msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketDeclineAutoSearchSettlement msg) { sendToServer((CustomPacketPayload) msg); }

    private static void handleTooltipQueryServer(PacketTooltipQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                var player = ctx.player();
                if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                var data = org.z2six.villageroverhaul.server.TooltipService.computeSnapshot(sp, msg.traderEntityId());
                ctx.reply(data);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] TooltipQuery handler error", t);
            }
        });
    }

    private static void handleTradeLocksQueryServer(PacketTradeLocksQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                ctx.reply(TradeLockService.computeSnapshot(sp));
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] TradeLocksQuery handler error", t);
            }
        });
    }

    private static void handleToggleTradeLockServer(PacketToggleTradeLock msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleToggleTradeLock(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] ToggleTradeLock handler error", t);
            }
        });
    }

    private static void handleRerollServer(PacketRequestReroll msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleReroll(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] Reroll handler error", t);
            }
        });
    }

    private static void handleSearchCatalogQueryServer(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleSearchCatalogQuery(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchCatalogQuery handler error", t);
            }
        });
    }

    private static void handleStartAutoSearchServer(PacketStartAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleStartAutoSearch(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] StartAutoSearch handler error", t);
            }
        });
    }

    private static void handleCancelAutoSearchServer(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleCancelAutoSearch(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] CancelAutoSearch handler error", t);
            }
        });
    }

    private static void handleContinueAutoSearchServer(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleContinueAutoSearch(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] ContinueAutoSearch handler error", t);
            }
        });
    }

    private static void handleRerollCooldownQueryServer(PacketRerollCooldownQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleRerollCooldownQuery(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] CooldownQuery handler error", t);
            }
        });
    }

    private static void handlePayAutoSearchSettlementServer(PacketPayAutoSearchSettlement msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handlePayAutoSearchSettlement(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PayAutoSearchSettlement handler error", t);
            }
        });
    }

    private static void handleDeclineAutoSearchSettlementServer(PacketDeclineAutoSearchSettlement msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleDeclineAutoSearchSettlement(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] DeclineAutoSearchSettlement handler error", t);
            }
        });
    }

    private static void handleVillagerStatsQueryServer(PacketVillagerStatsQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                int id = msg.villagerEntityId();
                var level = sp.serverLevel();
                if (level == null) {
                    ctx.reply(PacketVillagerStatsData.missing(id));
                    return;
                }

                var ent = level.getEntity(id);
                if (ent == null || !VillagerStatsService.isSupportedMerchantEntity(ent)) {
                    ctx.reply(PacketVillagerStatsData.missing(id));
                    return;
                }

                // Ensure server has stats assigned (no-op if already present)
                VillagerStatsService.ensureStats(ent);

                CompoundTag pd = ent.getPersistentData();
                if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                    ctx.reply(PacketVillagerStatsData.missing(id));
                    return;
                }

                CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);

                int g = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
                int t = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
                int i = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
                int h = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));

                ctx.reply(new PacketVillagerStatsData(id, true, g, t, i, h));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerStatsQuery handler error", t);
            }
        });
    }
}
