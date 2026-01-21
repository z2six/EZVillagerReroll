// neoforge\src\main\java\org\z2six\villageroverhaul\network\ServerHandlers.java
package org.z2six.villageroverhaul.network;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.logic.CostUtil;
import org.z2six.villageroverhaul.logic.MoneyBridge;
import org.z2six.villageroverhaul.logic.RerollExecutor;
import org.z2six.villageroverhaul.logic.RerollState;
import org.z2six.villageroverhaul.logic.TradeLockState;
import org.z2six.villageroverhaul.logic.WalletBridge;
import org.z2six.villageroverhaul.logic.VillagerTraitEffects;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.autoReroll.*;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsData;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsQuery;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsSync;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsUpdate;
import org.z2six.villageroverhaul.network.modes.PacketVillagerEatTest;
import org.z2six.villageroverhaul.network.modes.PacketVillagerForceBlock;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerUiPause;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.patrol.*;
import org.z2six.villageroverhaul.network.recruit.*;
import org.z2six.villageroverhaul.network.trades.PacketToggleTradeLock;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;
import org.z2six.villageroverhaul.server.CatalogBuilder;
import org.z2six.villageroverhaul.server.CombatSettingsService;
import org.z2six.villageroverhaul.server.SearchService;
import org.z2six.villageroverhaul.server.VillagerStatsService;
import org.z2six.villageroverhaul.server.RecruitService;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;
import org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService;
import org.z2six.villageroverhaul.server.ai.VillagerEatTestService;
import org.z2six.villageroverhaul.combat.CombatSettings;

// patrol packets

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleSyncConfigQuery(PacketSyncConfigQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            ServerSync.syncTo(sp);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleSyncConfigQuery failed", t);
        }
    }

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            // HARD GATE: villager trader must be recruited AND owned by this player.
            try {
                if (sp.containerMenu instanceof MerchantMenu menu) {
                    var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                    if (trader instanceof Villager vill) {
                        if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleReroll denied (player={} villager={})",
                                    sp.getGameProfile().getName(), vill.getUUID());
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            RerollExecutor.tryReroll(sp);

            // refresh tooltip snapshot immediately (cost breakdown + daily remaining)
            ctx.reply(org.z2six.villageroverhaul.server.TooltipService.computeSnapshot(sp, -1));

            sendCooldownStateSnapshot(sp, ctx);
            sendCurrentTradeLocksSnapshot(sp, ctx);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleReroll failed", t);
        }
    }

    public static void handleRerollCooldownQuery(PacketRerollCooldownQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            // If current trader is a villager and not owner, do not leak cooldown.
            try {
                if (sp.containerMenu instanceof MerchantMenu menu) {
                    var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                    if (trader instanceof Villager vill) {
                        if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            sendCooldownStateSnapshot(sp, ctx);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleRerollCooldownQuery failed", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int idx = msg.tradeIndex();
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleToggleTradeLock denied (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            long next = TradeLockState.toggle(vill, idx);
            long sanitized = TradeLockState.sanitizeMaskForSize(next, vill.getOffers().size());
            TradeLockState.setMask(vill, sanitized);
            try { org.z2six.villageroverhaul.server.VillagerHistoryService.addTradeLockToggle(vill, 1); } catch (Throwable ignored) {}

            ctx.reply(new PacketTradeLocks(menu.containerId, sanitized));
            ctx.reply(org.z2six.villageroverhaul.server.TooltipService.computeSnapshot(sp, vill.getId()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleToggleTradeLock failed", t);
        }
    }

    public static void handleSearchCatalogQuery(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleSearchCatalogQuery: villager not resolved for entityId={} (player={})",
                        msg.villagerEntityId(), sp.getGameProfile().getName());
                ctx.reply(PacketSearchCatalogData.minimal(msg.villagerEntityId(), List.of()));
                return;
            }

            // HARD GATE: catalog is a control feature
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                ctx.reply(PacketSearchCatalogData.minimal(msg.villagerEntityId(), List.of()));
                return;
            }

            List<net.minecraft.world.item.ItemStack> items;
            try {
                items = CatalogBuilder.buildCatalog(vill);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] handleSearchCatalogQuery: CatalogBuilder.buildCatalog failed (villager={})",
                        vill.getUUID(), t);
                items = List.of();
            }

            int offerCount = 0;
            try {
                offerCount = (vill.getOffers() == null) ? 0 : Math.max(0, vill.getOffers().size());
            } catch (Throwable ignored) {
                offerCount = 0;
            }

            long lockMask = 0L;
            try {
                lockMask = TradeLockState.getMask(vill);
            } catch (Throwable ignored) {
                lockMask = 0L;
            }

            try {
                long sanitized = TradeLockState.sanitizeMaskForSize(lockMask, offerCount);
                if (sanitized != lockMask) {
                    TradeLockState.setMask(vill, sanitized);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleSearchCatalogQuery: sanitized lock mask due to offer size change (villager={} before={} after={} offers={})",
                            vill.getUUID(),
                            Long.toUnsignedString(lockMask),
                            Long.toUnsignedString(sanitized),
                            offerCount);
                    lockMask = sanitized;

                    try {
                        org.z2six.villageroverhaul.server.TradeLockSyncService.syncToActiveTraders(vill, lockMask);
                    } catch (Throwable syncIgnored) {
                        // soft
                    }
                }
            } catch (Throwable ignored) {
                // keep lockMask as-is
            }

            int lockedCount = 0;
            try {
                lockedCount = Long.bitCount(lockMask);
            } catch (Throwable ignored) {
                lockedCount = 0;
            }

            int maxDeduct = Math.max(0, ServerConfig.maxDeductibleLockedOffers);
            int deductibleLocks = Math.min(lockedCount, maxDeduct);

            int effectiveOffers = Math.max(0, offerCount - deductibleLocks);

            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int effectivePaidOffers = Math.max(0, effectiveOffers - freeOffers);

            int costPerOffer = Math.max(0, ServerConfig.costPerOffer);
            long manualLong = (long) effectivePaidOffers * (long) costPerOffer;
            if (manualLong < 0L) manualLong = 0L;
            if (manualLong > Integer.MAX_VALUE) manualLong = Integer.MAX_VALUE;
            int manualCost = (int) manualLong;

            int hourlyCost;
            try {
                hourlyCost = SearchService.computeHourlyCostServer(vill);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleSearchCatalogQuery: computeHourlyCostServer failed (soft): {}", t.toString());
                hourlyCost = 0;
            }

            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleSearchCatalogQuery snapshot: villager={} offers={} locked={} deductibleLocks={} free={} paid={} manual={} hourly={}",
                        vill.getUUID(),
                        offerCount,
                        lockedCount,
                        deductibleLocks,
                        freeOffers,
                        effectivePaidOffers,
                        manualCost,
                        hourlyCost);
            }

            ctx.reply(new PacketSearchCatalogData(
                    vill.getId(),
                    items,
                    offerCount,
                    lockedCount,
                    effectivePaidOffers,
                    manualCost,
                    hourlyCost
            ));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleSearchCatalogQuery failed", t);
        }
    }

    public static void handleStartAutoSearch(PacketStartAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleStartAutoSearch denied (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            SearchService.start(sp, vill, msg.targets());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleStartAutoSearch failed", t);
        }
    }

    public static void handleCancelAutoSearch(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleCancelAutoSearch denied (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            SearchService.cancelByEntityId(sp, msg.villagerEntityId());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleCancelAutoSearch failed", t);
        }
    }

    public static void handleContinueAutoSearch(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        // no-op by design
    }

    public static void handlePayAutoSearchSettlement(PacketPayAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            SearchService.Settlement settlement = SearchService.getSettlement(vill);
            if (settlement == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePayAutoSearchSettlement: no settlement (villagerId={} uuid={})",
                        vill.getId(), vill.getUUID());
                return;
            }

            int settlementXp = 0;
            try {
                settlementXp = Math.max(0, SearchService.getSettlementTotalVillagerXp(vill));
            } catch (Throwable ignored) {
                settlementXp = 0;
            }

            int cost = SearchService.getSettlementFinalCost(vill);
            if (cost > 0 && !tryChargePlayer(sp, cost)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePayAutoSearchSettlement: charge failed (player={} cost={} villager={})",
                        sp.getGameProfile().getName(), cost, vill.getUUID());
                return;
            }

            int awardedXp = 0;
            try {
                awardedXp = SearchService.awardSettlementVillagerXpIfAny(vill, settlement);
            } catch (Throwable xpErr) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePayAutoSearchSettlement: awarding XP failed (soft) villager={}", vill.getUUID(), xpErr);
                awardedXp = 0;
            }

            SearchService.popSettlement(vill.getUUID());

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePayAutoSearchSettlement: success (player={} villager={} cost={} awardedXp={} settlementXp={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    cost,
                    awardedXp,
                    settlementXp
            );

            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePayAutoSearchSettlement failed", t);
        }
    }

    public static void handleDeclineAutoSearchSettlement(PacketDeclineAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            SearchService.Settlement settlement = SearchService.getSettlement(vill);
            if (settlement == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleDeclineAutoSearchSettlement: no settlement (villagerId={} uuid={})",
                        vill.getId(), vill.getUUID());
                return;
            }

            applyOffersFromOfferTagList(vill, SearchService.getSettlementOffersBeforeTag(vill), "settlement.offersBeforeTag");
            long lockMaskBefore = SearchService.getSettlementLockMaskBefore(vill);
            long sanitized = TradeLockState.sanitizeMaskForSize(lockMaskBefore, safeOfferSize(vill));

            TradeLockState.setMask(vill, sanitized);
            try {
                org.z2six.villageroverhaul.server.TradeLockSyncService.syncToActiveTraders(vill, sanitized);
            } catch (Throwable ignored) {}

            SearchService.popSettlement(vill.getUUID());
            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleDeclineAutoSearchSettlement failed", t);
        }
    }

    // =========================================================================================
    // PATROL HANDLERS
    // =========================================================================================

    public static void handlePatrolBegin(PacketPatrolBegin msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            if (!RecruitService.isRecruited(vill)) return;

            boolean createNew = msg.createNew();

            if (!createNew) {
                // Existing-only: send the list of saved routes to the client (do NOT auto-start).
                try {
                    var routes = VillagerBrain.listSavedPatrolRoutes(vill);
                    java.util.ArrayList<org.z2six.villageroverhaul.network.patrol.PacketPatrolRoutesData.RouteEntry> list =
                            new java.util.ArrayList<>(routes.size());
                    for (var r : routes) {
                        if (r == null) continue;
                        list.add(new org.z2six.villageroverhaul.network.patrol.PacketPatrolRoutesData.RouteEntry(
                                r.id(),
                                r.name(),
                                r.type() == null ? "" : r.type().id,
                                r.waypointCount()
                        ));
                    }
                    ctx.reply(new org.z2six.villageroverhaul.network.patrol.PacketPatrolRoutesData(vill.getId(), list));
                } catch (Throwable ignored) {
                    ctx.reply(new org.z2six.villageroverhaul.network.patrol.PacketPatrolRoutesData(vill.getId(), java.util.List.of()));
                }
                return;
            }

            // createNew == true
            VillagerBrain.beginPatrolSetup(vill, sp, true);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolBegin: begin new setup (player={} villager={})",
                    sp.getGameProfile().getName(), vill.getUUID());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolBegin failed", t);
        }
    }

    public static void handlePatrolAction(PacketPatrolAction msg, IPayloadContext ctx) {
        try {
            if (msg == null || msg.action() == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            if (!RecruitService.isRecruited(vill)) return;

            // Only setup owner can edit patrol during setup
            UUID owner = VillagerBrain.getPatrolSetupOwner(vill);
            if (owner == null || !owner.equals(sp.getUUID())) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolAction denied: not owner (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            switch (msg.action()) {
                case ADD_WAYPOINT -> {
                    if (msg.hasPos()) {
                        VillagerBrain.addPatrolWaypointFromClientPos(vill, new net.minecraft.world.phys.Vec3(msg.x(), msg.y(), msg.z()));
                    } else {
                        VillagerBrain.addPatrolWaypointAtCurrentPos(vill);
                    }

                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolAction: add waypoint (count={} player={} villager={})",
                            VillagerBrain.getPatrolWaypointCount(vill),
                            sp.getGameProfile().getName(),
                            vill.getUUID());
                }
                case FINALIZE -> {
                    VillagerBrain.markPatrolFinalized(vill);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolAction: finalized (awaiting route type) (player={} villager={})",
                            sp.getGameProfile().getName(), vill.getUUID());
                }
                case CANCEL -> {
                    VillagerBrain.cancelAndClearPatrol(vill);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolAction: canceled + cleared (player={} villager={})",
                            sp.getGameProfile().getName(), vill.getUUID());
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolAction failed", t);
        }
    }

    public static void handlePatrolRouteType(PacketPatrolSetRouteType msg, IPayloadContext ctx) {
        try {
            if (msg == null || msg.routeType() == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            if (!RecruitService.isRecruited(vill)) return;

            UUID owner = VillagerBrain.getPatrolSetupOwner(vill);
            if (owner == null || !owner.equals(sp.getUUID())) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolRouteType denied: not owner (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            VillagerBrain.setPatrolRouteTypeAndStart(vill, msg.routeType());
            try { org.z2six.villageroverhaul.server.VillagerHistoryService.addPatrolRouteRecorded(vill, 1); } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolRouteType: start patrol (type={} player={} villager={})",
                    msg.routeType(), sp.getGameProfile().getName(), vill.getUUID());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolRouteType failed", t);
        }
    }

    public static void handlePatrolSaveRoute(PacketPatrolSaveRoute msg, IPayloadContext ctx) {
        try {
            if (msg == null || msg.routeType() == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) return;

            // Only setup owner can save the currently-recorded route.
            UUID owner = VillagerBrain.getPatrolSetupOwner(vill);
            if (owner == null || !owner.equals(sp.getUUID())) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePatrolSaveRoute denied: not owner (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            VillagerBrain.PatrolRouteType rt = (msg.routeType() == PacketPatrolSetRouteType.RouteType.LINEAR)
                    ? VillagerBrain.PatrolRouteType.LINEAR
                    : VillagerBrain.PatrolRouteType.CIRCULAR;

            boolean ok = VillagerBrain.saveCurrentPatrolAsNewRouteAndStart(vill, msg.name(), rt);
            if (ok) {
                try { org.z2six.villageroverhaul.server.VillagerHistoryService.addPatrolRouteRecorded(vill, 1); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolSaveRoute failed", t);
        }
    }

    public static void handlePatrolRouteStart(PacketPatrolRouteStart msg, IPayloadContext ctx) {
        try {
            if (msg == null || msg.routeId() == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) return;

            VillagerBrain.startPatrolRoute(vill, msg.routeId());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolRouteStart failed", t);
        }
    }

    public static void handlePatrolRouteDelete(PacketPatrolRouteDelete msg, IPayloadContext ctx) {
        try {
            if (msg == null || msg.routeId() == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) return;

            VillagerBrain.deletePatrolRoute(vill, msg.routeId());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolRouteDelete failed", t);
        }
    }

    public static void handlePatrolRouteRename(PacketPatrolRouteRename msg, IPayloadContext ctx) {
        try {
            if (msg == null || msg.routeId() == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) return;

            VillagerBrain.renamePatrolRoute(vill, msg.routeId(), msg.newName());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolRouteRename failed", t);
        }
    }

    public static void handlePatrolInteractRequest(PacketPatrolInteractRequest msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) {
                ctx.reply(new PacketPatrolOpenGui(id, false, 0, false));
                return;
            }

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            boolean hasFinalizedRoute = VillagerBrain.hasAnySavedPatrolRoutes(vill);
            int waypointCount = VillagerBrain.getPatrolWaypointCount(vill);

            boolean canOpen = false;

            // Setup screen opens ONLY in PATROL_SETUP and ONLY for the owner.
            if (RecruitService.isRecruited(vill)
                    && VillagerBrain.getMode(vill) == VillagerBrain.Mode.PATROL_SETUP) {

                UUID owner = VillagerBrain.getPatrolSetupOwner(vill);
                if (owner != null && owner.equals(sp.getUUID())) {
                    canOpen = true;
                }
            }

            ctx.reply(new PacketPatrolOpenGui(id, canOpen, waypointCount, hasFinalizedRoute));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePatrolInteractRequest failed", t);
            try {
                ctx.reply(new PacketPatrolOpenGui(msg == null ? 0 : msg.villagerEntityId(), false, 0, false));
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================================
    // HELPERS
    // =========================================================================================

    public static void handleVillagerModeQuery(PacketVillagerModeQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                ctx.reply(new PacketVillagerModeData(msg.villagerEntityId(), "neutral"));
                return;
            }

            var mode = VillagerBrain.getMode(vill);
            ctx.reply(new PacketVillagerModeData(vill.getId(), mode == null ? "neutral" : mode.id));

        } catch (Throwable ignored) {}
    }

    public static void handleVillagerCombatModeQuery(PacketVillagerCombatModeQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                ctx.reply(new PacketVillagerCombatModeData(msg.villagerEntityId(), "off"));
                return;
            }

            var mode = VillagerBrain.getCombatMode(vill);
            ctx.reply(new PacketVillagerCombatModeData(vill.getId(), mode == null ? "off" : mode.id));

        } catch (Throwable ignored) {}
    }

    private static boolean tryChargePlayer(ServerPlayer sp, int cost) {
        try {
            boolean isTag = ServerConfig.isTagSpec(ServerConfig.costSpec);
            ResourceLocation id = isTag ? null : ResourceLocation.tryParse(ServerConfig.costSpec);

            if (ServerConfig.preferWallet && id != null && MoneyBridge.isLCPresent()) {
                if (MoneyBridge.tryExtract(sp, id, cost)) return true;
            }

            if (ServerConfig.preferWallet && id != null && WalletBridge.isLCPresent()) {
                if (WalletBridge.tryWithdrawFromWallet(sp, id, cost)) return true;
            }

            Ingredient ing = CostUtil.parseIngredient(ServerConfig.costSpec);
            return ing != Ingredient.EMPTY && CostUtil.consume(sp, ing, cost);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] tryChargePlayer failed (soft): {}", t.toString());
            return false;
        }
    }

    private static Villager resolveVillagerFor(ServerPlayer sp, int entityId) {
        try {
            ServerLevel lvl = sp.serverLevel();
            Entity e = lvl.getEntity(entityId);
            if (e instanceof Villager v) return v;

            if (sp.containerMenu instanceof MerchantMenu menu) {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Villager v) return v;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendCooldownStateSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;
            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            int remaining = RerollState.cooldownRemainingTicks(sp.serverLevel(), vill);

            int configured = 0;
            try {
                int base = ServerConfig.cooldownTicks;
                if (base > 0) {
                    try { VillagerStatsService.ensureStats(vill); } catch (Throwable ignored) {}

                    double pct = 0.0;
                    try { pct = VillagerTraitEffects.timelinessPct(vill); } catch (Throwable ignored) { pct = 0.0; }

                    configured = VillagerTraitEffects.applyCooldownPercent(base, pct);

                    if (configured <= 0) configured = 1;
                } else {
                    configured = 0;
                }
            } catch (Throwable ignored) {
                configured = Math.max(0, ServerConfig.cooldownTicks);
            }

            ctx.reply(new PacketRerollCooldownState(menu.containerId, remaining, configured));
        } catch (Throwable ignored) {}
    }

    private static void sendCurrentTradeLocksSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;
            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            long mask = TradeLockState.getMask(vill);
            ctx.reply(new PacketTradeLocks(menu.containerId, mask));
        } catch (Throwable ignored) {}
    }

    private static boolean applyOffersFromOfferTagList(Villager vill, ListTag offerList, String reason) {
        try {
            if (vill == null) return false;
            if (offerList == null || offerList.isEmpty()) return false;
            if (!(vill.level() instanceof ServerLevel level)) return false;

            var ops = net.minecraft.resources.RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());

            MerchantOffers decoded = new MerchantOffers();
            int n = Math.min(256, offerList.size());

            for (int i = 0; i < n; i++) {
                final int idx = i;

                net.minecraft.nbt.CompoundTag wrap;
                try {
                    wrap = offerList.getCompound(i);
                } catch (Throwable t) {
                    continue;
                }
                if (wrap == null) continue;

                Tag offerTag = wrap.get("v");
                if (offerTag == null) continue;

                var res = MerchantOffer.CODEC.parse(ops, offerTag);
                res.resultOrPartial(err ->
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] applyOffersFromOfferTagList: decode error (villager={} idx={} reason={}): {}",
                                vill.getUUID(), idx, reason, err)
                ).ifPresent(decoded::add);
            }

            MerchantOffers current = vill.getOffers();
            current.clear();
            current.addAll(decoded);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] applyOffersFromOfferTagList: applied offers (villager={} reason={} count={})",
                    vill.getUUID(), reason, decoded.size());
            return true;

        } catch (Throwable e) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] applyOffersFromOfferTagList failed (reason=" + reason + ")", e);
            return false;
        }
    }

    private static boolean trySetOffersReflect(Villager vill, MerchantOffers offers) {
        try {
            if (vill == null || offers == null) return false;

            String[] fieldNames = new String[]{"offers", "merchantOffers", "tradeOffers"};
            for (String name : fieldNames) {
                try {
                    Field f = vill.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(vill);
                    if (v instanceof MerchantOffers current) {
                        current.clear();
                        current.addAll(offers);
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {}
            }

            Class<?> c = vill.getClass().getSuperclass();
            while (c != null && c != Object.class) {
                for (String name : fieldNames) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        Object v = f.get(vill);
                        if (v instanceof MerchantOffers current) {
                            current.clear();
                            current.addAll(offers);
                            return true;
                        }
                    } catch (NoSuchFieldException ignored) {}
                }
                c = c.getSuperclass();
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] trySetOffersReflect: could not locate offers field (villager={})", vill.getUUID());
            return false;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] trySetOffersReflect failed (soft): {}", t.toString());
            return false;
        }
    }

    private static int safeOfferSize(Villager vill) {
        try {
            if (vill == null || vill.getOffers() == null) return 0;
            return Math.max(0, vill.getOffers().size());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void handleVillagerCommand(PacketVillagerCommand msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerCommand denied (player={} villager={} cmd={})",
                        sp.getGameProfile().getName(), vill.getUUID(), msg.command());
                return;
            }

            switch (msg.command()) {
                case IDLE -> VillagerBrain.idle(vill);
                case NEUTRAL -> VillagerBrain.neutral(vill);
                case FOLLOW -> org.z2six.villageroverhaul.server.ai.VillagerBrain.follow(vill, sp);
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerCommand: player={} villager={} cmd={}",
                    sp.getGameProfile().getName(), vill.getUUID(), msg.command());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleVillagerCommand failed", t);
        }
    }

    public static void handleVillagerCombatCommand(PacketVillagerCombatCommand msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerCombatCommand denied (player={} villager={} cmd={})",
                        sp.getGameProfile().getName(), vill.getUUID(), msg.command());
                return;
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerCombatCommand received (player={} villager={} cmd={})",
                    sp.getGameProfile().getName(), vill.getUUID(), msg.command());

            switch (msg.command()) {
                case OFF -> VillagerBrain.combatOff(vill);
                case FLEE -> VillagerBrain.combatFlee(vill);
                case DEFEND -> VillagerBrain.combatDefend(vill);
                case AGGRESSIVE -> VillagerBrain.combatAggressive(vill);
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleVillagerCombatCommand applied (player={} villager={} cmd={})",
                    sp.getGameProfile().getName(), vill.getUUID(), msg.command());

            try {
                var mode = VillagerBrain.getCombatMode(vill);
                ctx.reply(new PacketVillagerCombatModeData(vill.getId(), mode == null ? "off" : mode.id));
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleVillagerCombatCommand failed", t);
        }
    }

    public static void handleCombatSettingsQuery(PacketCombatSettingsQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (msg.global()) {
                CombatSettings settings = CombatSettingsService.getGlobal(sp.serverLevel());
                ctx.reply(new PacketCombatSettingsData(0, true, settings.toTag()));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettings query (global) by player={}",
                        sp.getGameProfile().getName());
                return;
            }

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                return;
            }

            CombatSettings settings = CombatSettingsService.getPerVillager(vill);
            if (settings == null) settings = CombatSettingsService.getGlobal(sp.serverLevel());

            ctx.reply(new PacketCombatSettingsData(vill.getId(), false, settings.toTag()));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettings query (villager={}) by player={}",
                    vill.getUUID(), sp.getGameProfile().getName());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleCombatSettingsQuery failed", t);
        }
    }

    public static void handleCombatSettingsSync(PacketCombatSettingsSync msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                return;
            }

            CombatSettings settings = CombatSettingsService.getGlobal(sp.serverLevel());
            CombatSettingsService.setPerVillager(vill, settings);

            ctx.reply(new PacketCombatSettingsData(vill.getId(), false, settings.toTag()));

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettings synced from global (villager={} player={})",
                    vill.getUUID(), sp.getGameProfile().getName());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleCombatSettingsSync failed", t);
        }
    }

    public static void handleVillagerForceBlock(PacketVillagerForceBlock msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                return;
            }
            if (!sp.hasPermissions(2)) {
                return;
            }

            int ticks = Math.max(1, Math.min(20 * 30, msg.ticks()));
            org.z2six.villageroverhaul.server.ai.VillagerBrain.forceBlockFor(vill, ticks);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Force block requested (villager={} ticks={})",
                    vill.getUUID(), ticks);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleVillagerForceBlock failed", t);
        }
    }

    public static void handleVillagerEatTest(PacketVillagerEatTest msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                return;
            }
            if (!sp.hasPermissions(2)) {
                return;
            }

            boolean ok = VillagerEatTestService.requestEatNearestFood(vill);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Eat test requested (villager={} ok={})",
                    vill.getUUID(), ok);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleVillagerEatTest failed", t);
        }
    }

    public static void handleCombatSettingsUpdate(PacketCombatSettingsUpdate msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            CombatSettings settings = CombatSettings.fromTag(msg.settings());

            if (msg.global()) {
                if (!sp.hasPermissions(2)) return;
                CombatSettingsService.setGlobal(sp.serverLevel(), settings);
                ctx.reply(new PacketCombatSettingsData(0, true, settings.toTag()));
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettings updated (global) by player={}",
                        sp.getGameProfile().getName());
                return;
            }

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                return;
            }

            CombatSettingsService.setPerVillager(vill, settings);
            ctx.reply(new PacketCombatSettingsData(vill.getId(), false, settings.toTag()));
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CombatSettings updated (villager={}) by player={}",
                    vill.getUUID(), sp.getGameProfile().getName());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleCombatSettingsUpdate failed", t);
        }
    }

    public static void handleVillagerUiPause(PacketVillagerUiPause msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                return;
            }

            VillagerBrain.setUiPaused(vill, msg.paused());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleVillagerUiPause failed", t);
        }
    }

    public static void handleOpenVillagerInventory(PacketOpenVillagerInventory msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);
            if (vill == null) return;

            // HARD GATE: inventory is a controls feature
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleOpenVillagerInventory denied (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            try {
                VillagerCombatLoadoutService.prepareForInventoryOpen(vill);
            } catch (Throwable ignored) {}

            // Open menu; write villager id to buf so client menu knows which entity to render
            sp.openMenu(
                    org.z2six.villageroverhaul.menu.VillagerInventoryMenu.providerFor(sp, vill),
                    buf -> buf.writeVarInt(vill.getId())
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleOpenVillagerInventory failed", t);
        }
    }

    // =====================
    // PERMISSION GATE
    // =====================

    public static void handleRecruitGateQuery(PacketRecruitGateQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int id = msg.villagerEntityId();
            Villager vill = resolveVillagerFor(sp, id);

            if (vill == null) {
                ctx.reply(new PacketRecruitGateData(id, false, false, false, ""));
                return;
            }

            boolean recruited = RecruitService.isRecruited(vill);
            boolean canUse = recruited && org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp);

            String byName = "";
            if (recruited) {
                try {
                    var pd = vill.getPersistentData();
                    if (pd != null && pd.contains(RecruitService.TAG_RECRUITED_BY_NAME)) {
                        byName = pd.getString(RecruitService.TAG_RECRUITED_BY_NAME);
                    }
                } catch (Throwable ignored) { byName = ""; }

                // Best-effort fallback: resolve from UUID via server cache.
                if ((byName == null || byName.isBlank())) {
                    try {
                        java.util.UUID rid = RecruitService.getRecruiterUuid(vill);
                        if (rid != null) {
                            Object cache = sp.server.getProfileCache();
                            if (cache != null) {
                                try {
                                    java.lang.reflect.Method mGet = cache.getClass().getMethod("get", java.util.UUID.class);
                                    Object opt = mGet.invoke(cache, rid);
                                    if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                                        Object gp = o.get();
                                        try {
                                            java.lang.reflect.Method mName = gp.getClass().getMethod("getName");
                                            Object n = mName.invoke(gp);
                                            if (n instanceof String s) byName = s;
                                        } catch (Throwable ignored2) {}
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            ctx.reply(new PacketRecruitGateData(id, true, recruited, canUse, byName == null ? "" : byName));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleRecruitGateQuery failed", t);
            try {
                ctx.reply(new PacketRecruitGateData(msg == null ? 0 : msg.villagerEntityId(), false, false, false, ""));
            } catch (Throwable ignored) {}
        }
    }

}
