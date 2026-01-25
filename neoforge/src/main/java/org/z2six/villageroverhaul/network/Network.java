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
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsData;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsQuery;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsUpdate;
import org.z2six.villageroverhaul.network.farming.PacketFarmingOverlayText;
import org.z2six.villageroverhaul.network.farming.PacketRegisterFarmingChest;
import org.z2six.villageroverhaul.network.farming.PacketRegisterFarmingWithdrawChest;
import org.z2six.villageroverhaul.network.farming.PacketRegisterFarmingWorkstation;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsData;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsQuery;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsUpdate;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsSync;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerEatTest;
import org.z2six.villageroverhaul.network.modes.PacketVillagerForceBlock;
import org.z2six.villageroverhaul.network.modes.PacketVillagerUiPause;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.patrol.*;
import org.z2six.villageroverhaul.network.recruit.*;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsQuery;
import org.z2six.villageroverhaul.network.attrs.PacketVillagerAttributesData;
import org.z2six.villageroverhaul.network.attrs.PacketVillagerAttributesQuery;
import org.z2six.villageroverhaul.network.history.ClientVillagerHistoryCache;
import org.z2six.villageroverhaul.network.history.PacketVillagerHistoryData;
import org.z2six.villageroverhaul.network.history.PacketVillagerHistoryQuery;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipData;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipQuery;
import org.z2six.villageroverhaul.network.trades.PacketToggleTradeLock;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocksQuery;
import org.z2six.villageroverhaul.network.trades.PacketVillagerTradesData;
import org.z2six.villageroverhaul.network.trades.PacketVillagerTradesQuery;
import org.z2six.villageroverhaul.network.trades.ClientVillagerTradesCache;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeStart;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeStop;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeState;
import org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen;
import org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnInfoScreen;
import org.z2six.villageroverhaul.network.respawn.PacketRespawnExecute;
import org.z2six.villageroverhaul.network.respawn.PacketRespawnInfoQuery;
import org.z2six.villageroverhaul.network.respawn.PacketRespawnPurge;
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

            // auto-trade (server-driven)
            r.playToServer(PacketAutoTradeStart.TYPE, PacketAutoTradeStart.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleAutoTradeStart(msg, ctx)));
            r.playToServer(PacketAutoTradeStop.TYPE, PacketAutoTradeStop.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleAutoTradeStop(msg, ctx)));

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

            // villager attributes query (server authoritative values)
            r.playToServer(PacketVillagerAttributesQuery.TYPE, PacketVillagerAttributesQuery.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerAttributesQueryServer(msg, ctx));

            // villager history query
            r.playToServer(PacketVillagerHistoryQuery.TYPE, PacketVillagerHistoryQuery.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerHistoryQueryServer(msg, ctx));

            // villager trades (offers + lock mask) query
            r.playToServer(PacketVillagerTradesQuery.TYPE, PacketVillagerTradesQuery.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerTradesQueryServer(msg, ctx));

            // respawn UI / actions
            r.playToServer(PacketRespawnInfoQuery.TYPE, PacketRespawnInfoQuery.STREAM_CODEC,
                    (msg, ctx) -> handleRespawnInfoQueryServer(msg, ctx));
            r.playToServer(PacketRespawnExecute.TYPE, PacketRespawnExecute.STREAM_CODEC,
                    (msg, ctx) -> handleRespawnExecuteServer(msg, ctx));
            r.playToServer(PacketRespawnPurge.TYPE, PacketRespawnPurge.STREAM_CODEC,
                    (msg, ctx) -> handleRespawnPurgeServer(msg, ctx));

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

            // ============================
            // Farming/storage serverbound
            // ============================
            r.playToServer(PacketFarmingSettingsQuery.TYPE, PacketFarmingSettingsQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleFarmingSettingsQuery(msg, ctx)));
            r.playToServer(PacketFarmingSettingsUpdate.TYPE, PacketFarmingSettingsUpdate.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleFarmingSettingsUpdate(msg, ctx)));
            r.playToServer(PacketRegisterFarmingChest.TYPE, PacketRegisterFarmingChest.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleRegisterFarmingChest(msg, ctx)));
            r.playToServer(PacketRegisterFarmingWithdrawChest.TYPE, PacketRegisterFarmingWithdrawChest.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleRegisterFarmingWithdrawChest(msg, ctx)));
            r.playToServer(PacketRegisterFarmingWorkstation.TYPE, PacketRegisterFarmingWorkstation.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleRegisterFarmingWorkstation(msg, ctx)));

            // ---- Clientbound (must be registered on BOTH sides for handshake) ----
            r.playToClient(PacketTooltipData.TYPE, PacketTooltipData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTooltipData", msg, ctx));
            r.playToClient(PacketSyncConfig.TYPE, PacketSyncConfig.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSyncConfig", msg, ctx));
            r.playToClient(PacketTradeLocks.TYPE, PacketTradeLocks.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onTradeLocks", msg, ctx));

            r.playToClient(PacketAutoTradeState.TYPE, PacketAutoTradeState.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoTradeState", msg, ctx));

            r.playToClient(PacketSearchCatalogData.TYPE, PacketSearchCatalogData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onSearchCatalogData", msg, ctx));
            r.playToClient(PacketOpenBusyScreen.TYPE, PacketOpenBusyScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenBusyScreen", msg, ctx));

            // farming settings UI data
            r.playToClient(PacketFarmingSettingsData.TYPE, PacketFarmingSettingsData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onFarmingSettingsData", msg, ctx));
            r.playToClient(PacketFarmingOverlayText.TYPE, PacketFarmingOverlayText.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onFarmingOverlayText", msg, ctx));

            // auto-search completion notification
            r.playToClient(PacketAutoSearchDone.TYPE, PacketAutoSearchDone.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchDone", msg, ctx));

            // cooldown state update
            r.playToClient(PacketRerollCooldownState.TYPE, PacketRerollCooldownState.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onRerollCooldownState", msg, ctx));

            // settlement/payment UI packets
            r.playToClient(PacketOpenAutoSearchPaymentScreen.TYPE, PacketOpenAutoSearchPaymentScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenAutoSearchPaymentScreen", msg, ctx));
            r.playToClient(PacketAutoSearchPaymentFailed.TYPE, PacketAutoSearchPaymentFailed.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchPaymentFailed", msg, ctx));
            r.playToClient(PacketAutoSearchSettlementCleared.TYPE, PacketAutoSearchSettlementCleared.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onAutoSearchSettlementCleared", msg, ctx));

            // villager stats data (we update cache directly; no client-only class refs)
            r.playToClient(PacketVillagerStatsData.TYPE, PacketVillagerStatsData.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerStatsDataClient(msg, ctx));

            // villager attributes data (we update cache directly; no client-only class refs)
            r.playToClient(PacketVillagerAttributesData.TYPE, PacketVillagerAttributesData.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerAttributesDataClient(msg, ctx));

            // villager history data (we update cache directly; no client-only class refs)
            r.playToClient(PacketVillagerHistoryData.TYPE, PacketVillagerHistoryData.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerHistoryDataClient(msg, ctx));

            // villager trades data (we update cache directly; no client-only class refs)
            r.playToClient(PacketVillagerTradesData.TYPE, PacketVillagerTradesData.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerTradesDataClient(msg, ctx));

            // respawn screens are opened client-side via ClientNetworkHandlers
            r.playToClient(PacketOpenRespawnAnchorScreen.TYPE, PacketOpenRespawnAnchorScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenRespawnAnchorScreen", msg, ctx));
            r.playToClient(PacketOpenRespawnInfoScreen.TYPE, PacketOpenRespawnInfoScreen.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onOpenRespawnInfoScreen", msg, ctx));

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
            r.playToClient(PacketPatrolRoutesData.TYPE, PacketPatrolRoutesData.STREAM_CODEC,
                    (msg, ctx) -> handlePatrolRoutesDataClient(msg, ctx));

            // Villager AI
            r.playToServer(PacketVillagerCommand.TYPE, PacketVillagerCommand.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerCommandServer(msg, ctx));
            r.playToServer(PacketVillagerCombatCommand.TYPE, PacketVillagerCombatCommand.STREAM_CODEC,
                    (msg, ctx) -> handleVillagerCombatCommandServer(msg, ctx));
            r.playToServer(PacketVillagerManualFarmingModeCommand.TYPE, PacketVillagerManualFarmingModeCommand.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerManualFarmingModeCommand(msg, ctx)));
            r.playToServer(PacketCombatSettingsQuery.TYPE, PacketCombatSettingsQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleCombatSettingsQuery(msg, ctx)));
            r.playToServer(PacketCombatSettingsSync.TYPE, PacketCombatSettingsSync.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleCombatSettingsSync(msg, ctx)));
            r.playToServer(PacketCombatSettingsUpdate.TYPE, PacketCombatSettingsUpdate.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleCombatSettingsUpdate(msg, ctx)));
            r.playToServer(PacketVillagerForceBlock.TYPE, PacketVillagerForceBlock.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerForceBlock(msg, ctx)));
            r.playToServer(PacketVillagerEatTest.TYPE, PacketVillagerEatTest.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerEatTest(msg, ctx)));
            r.playToServer(PacketVillagerUiPause.TYPE, PacketVillagerUiPause.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerUiPause(msg, ctx)));

            // Config resync (client requests server to resend PacketSyncConfig)
            r.playToServer(PacketSyncConfigQuery.TYPE, PacketSyncConfigQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleSyncConfigQuery(msg, ctx)));

            // ============================
            // Patrol serverbound
            // ============================
            r.playToServer(PacketVillagerModeQuery.TYPE, PacketVillagerModeQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerModeQuery(msg, ctx)));
            r.playToServer(PacketVillagerCombatModeQuery.TYPE, PacketVillagerCombatModeQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerCombatModeQuery(msg, ctx)));
            r.playToServer(PacketVillagerManualFarmingModeQuery.TYPE, PacketVillagerManualFarmingModeQuery.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handleVillagerManualFarmingModeQuery(msg, ctx)));

            r.playToClient(PacketVillagerModeData.TYPE, PacketVillagerModeData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onVillagerModeData", msg, ctx));
            r.playToClient(PacketVillagerCombatModeData.TYPE, PacketVillagerCombatModeData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onVillagerCombatModeData", msg, ctx));
            r.playToClient(PacketVillagerManualFarmingModeData.TYPE, PacketVillagerManualFarmingModeData.STREAM_CODEC,
                    (msg, ctx) -> dispatchToClientHandler("onVillagerManualFarmingModeData", msg, ctx));
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

            // patrol routes (multi-route support)
            r.playToServer(PacketPatrolRouteStart.TYPE, PacketPatrolRouteStart.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handlePatrolRouteStart(msg, ctx)));
            r.playToServer(PacketPatrolRouteDelete.TYPE, PacketPatrolRouteDelete.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handlePatrolRouteDelete(msg, ctx)));
            r.playToServer(PacketPatrolRouteRename.TYPE, PacketPatrolRouteRename.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handlePatrolRouteRename(msg, ctx)));
            r.playToServer(PacketPatrolSaveRoute.TYPE, PacketPatrolSaveRoute.STREAM_CODEC,
                    (msg, ctx) -> ctx.enqueueWork(() -> ServerHandlers.handlePatrolSaveRoute(msg, ctx)));


            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Network payloads registered (handshake-safe). distClient={}", isClientDist());
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

    private static void handleVillagerAttributesDataClient(PacketVillagerAttributesData msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientVillagerAttributesCache.accept(msg);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerAttributesDataClient failed (soft): {}", t.toString());
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void handleVillagerHistoryDataClient(PacketVillagerHistoryData msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientVillagerHistoryCache.accept(msg);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerHistoryDataClient failed (soft): {}", t.toString());
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void handleVillagerTradesDataClient(PacketVillagerTradesData msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    ClientVillagerTradesCache.accept(msg);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerTradesDataClient failed (soft): {}", t.toString());
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

    private static void handlePatrolRoutesDataClient(PacketPatrolRoutesData msg, IPayloadContext ctx) {
        try {
            ctx.enqueueWork(() -> {
                try {
                    if (!isClientDist()) return;
                    if (msg == null) return;

                    // Call ClientUI.acceptPatrolRoutesData(PacketPatrolRoutesData) via reflection.
                    Class<?> ui = Class.forName("org.z2six.villageroverhaul.client.ClientUI");
                    try {
                        ui.getMethod("acceptPatrolRoutesData", msg.getClass()).invoke(null, msg);
                    } catch (NoSuchMethodException ex) {
                        ui.getMethod("acceptPatrolRoutesData", Object.class).invoke(null, msg);
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolRoutesDataClient failed", t);
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

                int mot = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_MOTIVATION));
                int eff = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_EFFICIENCY));
                int pw  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_PLANT_WHISPERER));
                int rng = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_RANGER));

                ctx.reply(new PacketVillagerStatsData(id, true, g, t, i, h, vit, agi, str, arm, mot, eff, pw, rng));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerStatsQuery handler error", t);
            }
        });
    }

    private static void handleVillagerAttributesQueryServer(PacketVillagerAttributesQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                int id = msg.villagerEntityId();
                var level = sp.serverLevel();
                if (level == null) {
                    ctx.reply(PacketVillagerAttributesData.missing(id));
                    return;
                }

                var ent = level.getEntity(id);
                if (ent == null || !VillagerStatsService.isSupportedMerchantEntity(ent)) {
                    ctx.reply(PacketVillagerAttributesData.missing(id));
                    return;
                }

                if (!(ent instanceof net.minecraft.world.entity.LivingEntity le)) {
                    ctx.reply(PacketVillagerAttributesData.missing(id));
                    return;
                }

                // Ensure combat modifiers are applied before we snapshot attribute values.
                // Otherwise, the stats delta section can disagree with the raw Attributes section.
                try { org.z2six.villageroverhaul.server.VillagerCombatAttributeService.applyCombatModifiers(ent); } catch (Throwable ignored) {}

                final double EPS = 1.0E-6;
                java.util.ArrayList<PacketVillagerAttributesData.Entry> list = new java.util.ArrayList<>(32);

                // Only send "instantiated" attributes and hide pure-zero noise (but always show core combat ones).
                java.util.Set<net.minecraft.resources.ResourceLocation> always = java.util.Set.of(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("generic.max_health"),
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("generic.movement_speed"),
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("generic.attack_damage"),
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("generic.armor")
                );

                var holders = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.holders().toList();
                for (var holder : holders) {
                    var inst = le.getAttribute(holder);
                    if (inst == null) continue;

                    var key = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(holder.value());
                    if (key == null) continue;

                    double base = inst.getBaseValue();
                    // Some attributes (notably armor) can be clamped by vanilla in getValue().
                    // We want the Overview tab to reflect the actual modifiers applied by our stats system,
                    // so we compute the raw value from modifiers without any extra clamps.
                    double val = rawAttributeValue(inst);

                    boolean keep = always.contains(key) || Math.abs(base) > EPS || Math.abs(val) > EPS;
                    if (!keep) continue;

                    list.add(new PacketVillagerAttributesData.Entry(key, base, val));
                    if (list.size() >= 128) break;
                }

                ctx.reply(new PacketVillagerAttributesData(id, true, list));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerAttributesQuery handler error", t);
            }
        });
    }

    private static void handleVillagerHistoryQueryServer(PacketVillagerHistoryQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                int id = msg.villagerEntityId();
                var level = sp.serverLevel();
                if (level == null) {
                    ctx.reply(PacketVillagerHistoryData.missing(id));
                    return;
                }

                var ent = level.getEntity(id);
                if (!(ent instanceof Villager vill)) {
                    ctx.reply(PacketVillagerHistoryData.missing(id));
                    return;
                }

                if (!RecruitService.isRecruited(vill)) {
                    ctx.reply(PacketVillagerHistoryData.missing(id));
                    return;
                }

                ctx.reply(org.z2six.villageroverhaul.server.VillagerHistoryService.snapshot(vill));
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerHistoryQuery handler error", t);
            }
        });
    }

    private static void handleVillagerTradesQueryServer(PacketVillagerTradesQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;

                int id = msg.villagerEntityId();
                var level = sp.serverLevel();
                if (level == null) {
                    ctx.reply(PacketVillagerTradesData.missing(id));
                    return;
                }

                var ent = level.getEntity(id);
                if (!(ent instanceof net.minecraft.world.entity.npc.AbstractVillager av)) {
                    ctx.reply(PacketVillagerTradesData.missing(id));
                    return;
                }

                var offers = av.getOffers();
                int offerCount = offers == null ? 0 : offers.size();
                int n = Math.max(0, Math.min(64, offerCount));

                java.util.ArrayList<ItemStack> results = new java.util.ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    try {
                        var offer = offers.get(i);
                        if (offer == null) {
                            results.add(ItemStack.EMPTY);
                            continue;
                        }
                        results.add(offer.getResult().copy());
                    } catch (Throwable ignored) {
                        results.add(ItemStack.EMPTY);
                    }
                }

                long mask = 0L;
                if (ent instanceof Villager vill) {
                    try {
                        long raw = org.z2six.villageroverhaul.logic.TradeLockState.getMask(vill);
                        mask = org.z2six.villageroverhaul.logic.TradeLockState.sanitizeMaskForSize(raw, n);
                    } catch (Throwable ignored) {
                        mask = 0L;
                    }
                }

                ctx.reply(new PacketVillagerTradesData(id, true, mask, results));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerTradesQuery handler error", t);
            }
        });
    }

    private static void handleRespawnInfoQueryServer(PacketRespawnInfoQuery msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                var level = sp.serverLevel();
                if (level == null) return;

                java.util.UUID rid = msg == null ? null : msg.respawnId();
                if (rid == null) return;

                var snap = org.z2six.villageroverhaul.server.RespawnService.getForOwner(sp, level, rid);
                if (snap == null) return;

                int respawnCost = org.z2six.villageroverhaul.server.RespawnService.computeRespawnCost(snap.recruitCostAtDeath);

                ctx.reply(new PacketOpenRespawnInfoScreen(
                        msg.anchorX(), msg.anchorY(), msg.anchorZ(),
                        rid.getMostSignificantBits(), rid.getLeastSignificantBits(),
                        snap.recruitCostAtDeath,
                        respawnCost,
                        snap.deaths,
                        snap.villagerNbt == null ? new net.minecraft.nbt.CompoundTag() : snap.villagerNbt
                ));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnInfoQuery handler error", t);
            }
        });
    }

    private static void handleRespawnExecuteServer(PacketRespawnExecute msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                var level = sp.serverLevel();
                if (level == null) return;

                java.util.UUID rid = msg == null ? null : msg.respawnId();
                if (rid == null) return;

                var snap = org.z2six.villageroverhaul.server.RespawnService.getForOwner(sp, level, rid);
                if (snap == null) return;

                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(msg.anchorX(), msg.anchorY(), msg.anchorZ());
                var spawned = org.z2six.villageroverhaul.server.RespawnService.respawn(sp, level, snap, pos);
                if (spawned != null) {
                    try {
                        java.util.List<org.z2six.villageroverhaul.server.RespawnSavedData.Snapshot> snaps =
                                org.z2six.villageroverhaul.server.RespawnService.listForOwner(sp, level);
                        java.util.ArrayList<org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen.Entry> entries =
                                new java.util.ArrayList<>();
                        for (org.z2six.villageroverhaul.server.RespawnSavedData.Snapshot s : snaps) {
                            if (s == null || s.respawnId == null) continue;
                            int c = org.z2six.villageroverhaul.server.RespawnService.computeRespawnCost(s.recruitCostAtDeath);
                            entries.add(new org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen.Entry(
                                    s.respawnId,
                                    s.nameJson == null ? "" : s.nameJson,
                                    s.professionId == null ? "" : s.professionId,
                                    c,
                                    Math.max(0, s.deaths)
                            ));
                        }
                        ctx.reply(new org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen(
                                pos.getX(), pos.getY(), pos.getZ(), entries
                        ));
                    } catch (Throwable ignored) {}
                }

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnExecute handler error", t);
            }
        });
    }

    private static void handleRespawnPurgeServer(PacketRespawnPurge msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
                var level = sp.serverLevel();
                if (level == null) return;

                java.util.UUID rid = msg == null ? null : msg.respawnId();
                if (rid == null) return;

                org.z2six.villageroverhaul.server.RespawnService.purgeForOwner(sp, level, rid);

                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(msg.anchorX(), msg.anchorY(), msg.anchorZ());
                try {
                    java.util.List<org.z2six.villageroverhaul.server.RespawnSavedData.Snapshot> snaps =
                            org.z2six.villageroverhaul.server.RespawnService.listForOwner(sp, level);
                    java.util.ArrayList<org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen.Entry> entries =
                            new java.util.ArrayList<>();
                    for (org.z2six.villageroverhaul.server.RespawnSavedData.Snapshot s : snaps) {
                        if (s == null || s.respawnId == null) continue;
                        int c = org.z2six.villageroverhaul.server.RespawnService.computeRespawnCost(s.recruitCostAtDeath);
                        entries.add(new org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen.Entry(
                                s.respawnId,
                                s.nameJson == null ? "" : s.nameJson,
                                s.professionId == null ? "" : s.professionId,
                                c,
                                Math.max(0, s.deaths)
                        ));
                    }
                    ctx.reply(new org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen(
                            pos.getX(), pos.getY(), pos.getZ(), entries
                    ));
                } catch (Throwable ignored) {}

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnPurge handler error", t);
            }
        });
    }

    private static double rawAttributeValue(net.minecraft.world.entity.ai.attributes.AttributeInstance inst) {
        try {
            if (inst == null) return 0.0;

            double base = inst.getBaseValue();
            if (Double.isNaN(base) || Double.isInfinite(base)) base = 0.0;

            double value = base;

            // ADD_VALUE
            for (var mod : inst.getModifiers()) {
                if (mod == null) continue;
                if (mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
                    value += mod.amount();
                }
            }

            // ADD_MULTIPLIED_BASE
            for (var mod : inst.getModifiers()) {
                if (mod == null) continue;
                if (mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                    value += base * mod.amount();
                }
            }

            // ADD_MULTIPLIED_TOTAL
            for (var mod : inst.getModifiers()) {
                if (mod == null) continue;
                if (mod.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                    value *= 1.0 + mod.amount();
                }
            }

            if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
            return value;
        } catch (Throwable ignored) {
            try {
                return inst == null ? 0.0 : inst.getValue();
            } catch (Throwable ignored2) {
                return 0.0;
            }
        }
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

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Recruited villager: player={} villagerUuid={} cost={}",
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
