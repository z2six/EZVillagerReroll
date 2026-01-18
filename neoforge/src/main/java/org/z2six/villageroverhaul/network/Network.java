// neoforge\src\main\java\org\z2six\villageroverhaul\network\Network.java
package org.z2six.villageroverhaul.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.autoReroll.*;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsData;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsQuery;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsUpdate;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsSync;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerUiPause;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.patrol.*;
import org.z2six.villageroverhaul.network.recruit.*;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsQuery;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipData;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipQuery;
import org.z2six.villageroverhaul.network.trades.PacketToggleTradeLock;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocksQuery;
import org.z2six.villageroverhaul.server.RecruitService;
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

            // cooldown state query
            r.playToServer(PacketRerollCooldownQuery.TYPE, PacketRerollCooldownQuery.STREAM_CODEC,
                    (msg, ctx) -> handleRerollCooldownQueryServer(msg, ctx));

            // settlement payment actions
            r.playToServer(PacketPayAutoSearchSettlement.TYPE, PacketPayAutoSearchSettlement.STREAM_CODEC,
                    (msg, ctx) -> handlePayAutoSearchSettlementServer(msg, ctx));
            r.playToServer(PacketDeclineAutoSearchSettlement.TYPE, PacketDeclineAutoSearchSettlement.STREAM_CODEC,
                    (msg, ctx) -> handleDeclineAutoSearchSettlementServer(msg, ctx));

            // villager stats query
            r.playToServer(PacketVillagerStatsQuery.TYPE, PacketVillagerStatsQuery.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerStatsQueryServer(msg, ctx));

            // ============================
            // Recruit serverbound
            // ============================
            r.playToServer(PacketRecruitCostQuery.TYPE, PacketRecruitCostQuery.STREAM_CODEC,
                    (msg, ctx) -> handleRecruitCostQueryServer(msg, ctx));
            r.playToServer(PacketRecruitVillager.TYPE, PacketRecruitVillager.STREAM_CODEC,
                    (msg, ctx) -> handleRecruitVillagerServer(msg, ctx));

            // ============================
            // Patrol serverbound
            // ============================
            r.playToServer(PacketPatrolBegin.TYPE, PacketPatrolBegin.STREAM_CODEC,
                    (msg, ctx) -> handlePatrolBeginServer(msg, ctx));
            r.playToServer(PacketPatrolAction.TYPE, PacketPatrolAction.STREAM_CODEC,
                    (msg, ctx) -> handlePatrolActionServer(msg, ctx));
            r.playToServer(PacketPatrolSetRouteType.TYPE, PacketPatrolSetRouteType.STREAM_CODEC,
                    (msg, ctx) -> handlePatrolRouteTypeServer(msg, ctx));
            r.playToServer(PacketPatrolInteractRequest.TYPE, PacketPatrolInteractRequest.STREAM_CODEC,
                    (msg, ctx) -> handlePatrolInteractRequestServer(msg, ctx));

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

            // auto-search completion notification
            r.playToClient(PacketAutoSearchDone.TYPE, PacketAutoSearchDone.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchDone", msg, ctx));

            // cooldown state update
            r.playToClient(PacketRerollCooldownState.TYPE, PacketRerollCooldownState.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRerollCooldownState", msg, ctx));

            // settlement/payment UI packets
            r.playToClient(PacketOpenAutoSearchPaymentScreen.TYPE, PacketOpenAutoSearchPaymentScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenAutoSearchPaymentScreen", msg, ctx));
            r.playToClient(PacketAutoSearchSettlementCleared.TYPE, PacketAutoSearchSettlementCleared.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchSettlementCleared", msg, ctx));

            // villager stats data (we update cache directly; no client-only class refs)
            r.playToClient(PacketVillagerStatsData.TYPE, PacketVillagerStatsData.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerStatsDataClient(msg, ctx));

            // ============================
            // Recruit clientbound
            // ============================
            r.playToClient(PacketOpenRecruitScreen.TYPE, PacketOpenRecruitScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenRecruitScreen", msg, ctx));
            r.playToClient(PacketRecruitCostData.TYPE, PacketRecruitCostData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRecruitCostData", msg, ctx));
            r.playToClient(PacketRecruitResult.TYPE, PacketRecruitResult.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRecruitResult", msg, ctx));

            // ============================
            // Patrol clientbound
            // ============================
            // We do NOT require a ClientNetworkHandlers method; we route directly to ClientUI via reflection.
            r.playToClient(PacketPatrolOpenGui.TYPE, PacketPatrolOpenGui.STREAM_CODEC,
                    (msg, ctx) -> handlePatrolOpenGuiClient(msg, ctx));

            // Villager AI
            r.playToServer(PacketVillagerCommand.TYPE, PacketVillagerCommand.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerCommandServer(msg, ctx));
            r.playToServer(PacketVillagerCombatCommand.TYPE, PacketVillagerCombatCommand.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerCombatCommandServer(msg, ctx));
            r.playToServer(PacketCombatSettingsQuery.TYPE, PacketCombatSettingsQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleCombatSettingsQuery(msg, ctx)));
            r.playToServer(PacketCombatSettingsSync.TYPE, PacketCombatSettingsSync.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleCombatSettingsSync(msg, ctx)));
            r.playToServer(PacketCombatSettingsUpdate.TYPE, PacketCombatSettingsUpdate.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleCombatSettingsUpdate(msg, ctx)));
            r.playToServer(PacketVillagerUiPause.TYPE, PacketVillagerUiPause.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerUiPause(msg, ctx)));

            // ============================
            // Patrol serverbound
            // ============================
            r.playToServer(PacketVillagerModeQuery.TYPE, PacketVillagerModeQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerModeQuery(msg, ctx)));
            r.playToServer(PacketVillagerCombatModeQuery.TYPE, PacketVillagerCombatModeQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerCombatModeQuery(msg, ctx)));

            r.playToClient(PacketVillagerModeData.TYPE, PacketVillagerModeData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onVillagerModeData", msg, ctx));
            r.playToClient(PacketVillagerCombatModeData.TYPE, PacketVillagerCombatModeData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onVillagerCombatModeData", msg, ctx));
            r.playToClient(PacketCombatSettingsData.TYPE, PacketCombatSettingsData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onCombatSettingsData", msg, ctx));

            // === Recruit gate () ===
            r.playToServer(PacketRecruitGateQuery.TYPE, PacketRecruitGateQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleRecruitGateQuery(msg, ctx)));

            r.playToClient(PacketRecruitGateData.TYPE, PacketRecruitGateData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRecruitGateData", msg, ctx));

            // open villager inventory menu
            r.playToServer(PacketOpenVillagerInventory.TYPE, PacketOpenVillagerInventory.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleOpenVillagerInventory(msg, ctx)));


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
            ctx.enqueueWork(() -> {
                try {
                    ClientVillagerStatsCache.accept(msg);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerStatsDataClient failed (soft): {}", t.toString());
                }
            });
        } catch (Throwable ignored) {}
    }

    // ============================
    // Patrol clientbound handler
    // ============================
    private static void handlePatrolOpenGuiClient(PacketPatrolOpenGui msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    if (!isClientDist()) return;
                    if (msg == null) return;

                    // Call ClientUI.acceptPatrolOpenGui(PacketPatrolOpenGui) via reflection to avoid hard client refs.
                    Class<?> ui = Class.forName("org.z2six.villageroverhaul.client.ClientUI");
                    try {
                        ui.getMethod("acceptPatrolOpenGui", msg.getClass()).invoke(null, msg);
                    } catch (NoSuchMethodException ex) {
                        ui.getMethod("acceptPatrolOpenGui", Object.class).invoke(null, msg);
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolOpenGuiClient failed", t);
                }
            });
        } catch (Throwable ignored) {}
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

    private static void handleVillagerCommandServer(PacketVillagerCommand msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleVillagerCommand(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCommand handler error", t);
            }
        });
    }

    private static void handleVillagerCombatCommandServer(PacketVillagerCombatCommand msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handleVillagerCombatCommand(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatCommand handler error", t);
            }
        });
    }

    // ============================
    // Patrol serverbound forwarding
    // ============================
    private static void handlePatrolBeginServer(PacketPatrolBegin msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handlePatrolBegin(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolBegin handler error", t);
            }
        });
    }

    private static void handlePatrolActionServer(PacketPatrolAction msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handlePatrolAction(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolAction handler error", t);
            }
        });
    }

    private static void handlePatrolRouteTypeServer(PacketPatrolSetRouteType msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handlePatrolRouteType(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolRouteType handler error", t);
            }
        });
    }

    private static void handlePatrolInteractRequestServer(PacketPatrolInteractRequest msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                ServerHandlers.handlePatrolInteractRequest(msg, ctx);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolInteractRequest handler error", t);
            }
        });
    }

    // existing convenience overloads
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

    public static void sendToServer(PacketPayAutoSearchSettlement msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketDeclineAutoSearchSettlement msg) { sendToServer((CustomPacketPayload) msg); }

    // recruit convenience
    public static void sendToServer(PacketRecruitCostQuery msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketRecruitVillager msg) { sendToServer((CustomPacketPayload) msg); }

    // patrol convenience (optional but nice)
    public static void sendToServer(PacketPatrolBegin msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketPatrolAction msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketPatrolSetRouteType msg) { sendToServer((CustomPacketPayload) msg); }
    public static void sendToServer(PacketPatrolInteractRequest msg) { sendToServer((CustomPacketPayload) msg); }

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

                VillagerStatsService.ensureStats(ent);

                CompoundTag pd = ent.getPersistentData();
                if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                    ctx.reply(PacketVillagerStatsData.missing(id));
                    return;
                }

                CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);

                int g  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
                int t  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
                int i  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
                int h  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));

                int vit = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_VITALITY));
                int agi = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_AGILITY));
                int str = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_STRENGTH));
                int arm = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_ARMOR));

                ctx.reply(new PacketVillagerStatsData(id, true, g, t, i, h, vit, agi, str, arm));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerStatsQuery handler error", t);
            }
        });
    }

    // =========================================================================================
    // Recruit handlers
    // =========================================================================================

    private static void handleRecruitCostQueryServer(PacketRecruitCostQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                int id = msg.villagerEntityId();
                Villager vill = getVillagerById(sp, id);
                if (vill == null) {
                    ctx.reply(PacketRecruitCostData.missing(id));
                    return;
                }

                boolean recruited = RecruitService.isRecruited(vill);
                boolean eligible = RecruitService.isEligible(vill);

                int cost = 0;
                String m = "";

                if (!eligible) {
                    m = vill.isBaby() ? "Too young" : "Not eligible";
                } else if (recruited) {
                    m = "Already recruited";
                } else {
                    cost = Math.max(0, RecruitService.computeRecruitCost(vill));
                }

                ctx.reply(new PacketRecruitCostData(id, true, eligible, recruited, cost, m));

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] RecruitCostQuery: player={} villagerId={} eligible={} recruited={} cost={}",
                        sp.getGameProfile().getName(), id, eligible, recruited, cost);

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] RecruitCostQuery handler error", t);
                try {
                    ctx.reply(PacketRecruitCostData.missing(msg == null ? 0 : msg.villagerEntityId()));
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void handleRecruitVillagerServer(PacketRecruitVillager msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                int id = msg.villagerEntityId();
                Villager vill = getVillagerById(sp, id);
                if (vill == null) {
                    ctx.reply(new PacketRecruitResult(id, false, false, 0, "Missing villager"));
                    return;
                }

                boolean eligible = RecruitService.isEligible(vill);
                boolean recruited = RecruitService.isRecruited(vill);

                if (!eligible) {
                    ctx.reply(new PacketRecruitResult(id, false, recruited, 0, vill.isBaby() ? "Too young" : "Not eligible"));
                    return;
                }
                if (recruited) {
                    ctx.reply(new PacketRecruitResult(id, false, true, 0, "Already recruited"));
                    return;
                }

                int cost = Math.max(0, RecruitService.computeRecruitCost(vill));
                if (cost > 0) {
                    boolean paid = tryConsumeItem(sp, Items.EMERALD, cost);
                    if (!paid) {
                        ctx.reply(new PacketRecruitResult(id, false, false, 0, "Not enough emeralds"));
                        return;
                    }
                }

                boolean marked = RecruitService.markRecruited(sp, vill);
                if (!marked) {
                    if (cost > 0) {
                        try {
                            ItemStack refund = new ItemStack(Items.EMERALD, cost);
                            boolean added = sp.getInventory().add(refund);
                            if (!added) {
                                sp.drop(refund, false);
                            }
                        } catch (Throwable ignored) {}
                    }
                    ctx.reply(new PacketRecruitResult(id, false, false, 0, "Failed to recruit (server error)"));
                    return;
                }

                try {
                    org.z2six.villageroverhaul.combat.CombatSettings globalSettings =
                            org.z2six.villageroverhaul.server.CombatSettingsService.getGlobal(sp.serverLevel());
                    org.z2six.villageroverhaul.server.CombatSettingsService.setPerVillager(vill, globalSettings);
                } catch (Throwable ignored) {}

                ctx.reply(new PacketRecruitResult(id, true, true, cost, "Recruited!"));

                VillagerOverhaul.LOG().info("[VillagerOverhaul] Recruited villager: player={} villagerUuid={} cost={}",
                        sp.getGameProfile().getName(), vill.getUUID(), cost);

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] RecruitVillager handler error", t);
                try {
                    ctx.reply(new PacketRecruitResult(msg == null ? 0 : msg.villagerEntityId(), false, false, 0, "Server error"));
                } catch (Throwable ignored) {}
            }
        });
    }

    private static Villager getVillagerById(net.minecraft.server.level.ServerPlayer sp, int entityId) {
        try {
            if (sp == null) return null;
            var level = sp.serverLevel();
            if (level == null) return null;
            Entity e = level.getEntity(entityId);
            if (!(e instanceof Villager vill)) return null;
            return vill;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean tryConsumeItem(net.minecraft.server.level.ServerPlayer sp, Item item, int count) {
        try {
            if (sp == null || item == null) return false;
            if (count <= 0) return true;

            Container inv = sp.getInventory();
            if (inv == null) return false;

            int have = 0;
            int size = inv.getContainerSize();
            for (int slot = 0; slot < size; slot++) {
                ItemStack s = inv.getItem(slot);
                if (!s.isEmpty() && s.is(item)) have += s.getCount();
                if (have >= count) break;
            }

            if (have < count) return false;

            int remaining = count;
            for (int slot = 0; slot < size && remaining > 0; slot++) {
                ItemStack s = inv.getItem(slot);
                if (s.isEmpty() || !s.is(item)) continue;

                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;

                if (s.isEmpty()) inv.setItem(slot, ItemStack.EMPTY);
            }

            try { inv.setChanged(); } catch (Throwable ignored) {}
            return remaining <= 0;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tryConsumeItem failed (soft): {}", t.toString());
            return false;
        }
    }

    public static void sendToServer(PacketRecruitGateQuery msg) { sendToServer((CustomPacketPayload) msg); }

}
