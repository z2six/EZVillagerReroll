// neoforge\src\main\java\org\z2six\villageroverhaul\network\ServerHandlers.java
package org.z2six.villageroverhaul.network;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.logic.RerollExecutor;
import org.z2six.villageroverhaul.logic.RerollState;
import org.z2six.villageroverhaul.logic.TradeLockState;
import org.z2six.villageroverhaul.logic.VillagerTraitEffects;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.network.autoReroll.*;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsData;
import org.z2six.villageroverhaul.network.farming.PacketFarmingProfilesData;
import org.z2six.villageroverhaul.network.farming.PacketFarmingProfilesQuery;
import org.z2six.villageroverhaul.network.farming.PacketFarmingProfileDelete;
import org.z2six.villageroverhaul.network.farming.PacketFarmingProfileUpsert;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsQuery;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsUpdate;
import org.z2six.villageroverhaul.network.farming.PacketFarmingOverlayText;
import org.z2six.villageroverhaul.network.farming.PacketRegisterFarmingChest;
import org.z2six.villageroverhaul.network.farming.PacketRegisterFarmingWithdrawChest;
import org.z2six.villageroverhaul.network.farming.PacketRegisterFarmingWorkstation;
import org.z2six.villageroverhaul.network.trading.PacketRegisterTradingHall;
import org.z2six.villageroverhaul.network.customcommands.PacketCcActionDetailData;
import org.z2six.villageroverhaul.network.customcommands.PacketCcActionDetailQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcAddWaypoint;
import org.z2six.villageroverhaul.network.customcommands.PacketCcAddWaitStep;
import org.z2six.villageroverhaul.network.customcommands.PacketCcBeginRecord;
import org.z2six.villageroverhaul.network.customcommands.PacketCcBeginTeaching;
import org.z2six.villageroverhaul.network.customcommands.PacketCcCancelRecord;
import org.z2six.villageroverhaul.network.customcommands.PacketCcRecordLook;
import org.z2six.villageroverhaul.network.customcommands.PacketCcSetLookDuration;
import org.z2six.villageroverhaul.network.customcommands.PacketCcDeleteAction;
import org.z2six.villageroverhaul.network.customcommands.PacketCcListData;
import org.z2six.villageroverhaul.network.customcommands.PacketCcListQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcSaveTaughtAction;
import org.z2six.villageroverhaul.network.customcommands.PacketCcSetCombatOverride;
import org.z2six.villageroverhaul.network.customcommands.PacketCcSetChestRules;
import org.z2six.villageroverhaul.network.customcommands.PacketCcStopTeaching;
import org.z2six.villageroverhaul.network.customcommands.PacketCcTeachSessionData;
import org.z2six.villageroverhaul.network.customcommands.PacketCcTeachSessionQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcUpdateActionMeta;
import org.z2six.villageroverhaul.network.customcommands.PacketCcUpdateActionStepLookDuration;
import org.z2six.villageroverhaul.network.customcommands.PacketCcUpdateActionStepRules;
import org.z2six.villageroverhaul.network.customcommands.PacketCcUpdateActionStepWait;
import org.z2six.villageroverhaul.network.customcommands.PacketCcWaitState;
import org.z2six.villageroverhaul.network.customcommands.PacketCcChatListenData;
import org.z2six.villageroverhaul.network.customcommands.PacketCcChatListenQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcChatListenSet;
import org.z2six.villageroverhaul.network.chatcommands.PacketPlayerChatCommandsData;
import org.z2six.villageroverhaul.network.chatcommands.PacketPlayerChatCommandsQuery;
import org.z2six.villageroverhaul.network.chatcommands.PacketPlayerChatCommandsUpdate;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsData;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsQuery;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsSync;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsUpdate;
import org.z2six.villageroverhaul.network.modes.PacketVillagerEatTest;
import org.z2six.villageroverhaul.network.modes.PacketVillagerForceBlock;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerUiPause;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.patrol.*;
import org.z2six.villageroverhaul.network.recruit.*;
import org.z2six.villageroverhaul.network.trades.PacketToggleTradeLock;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocks;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeStart;
import org.z2six.villageroverhaul.network.autotrade.PacketAutoTradeStop;
import org.z2six.villageroverhaul.server.CatalogBuilder;
import org.z2six.villageroverhaul.server.CombatSettingsService;
import org.z2six.villageroverhaul.server.AutoTradeServerService;
import org.z2six.villageroverhaul.server.SearchService;
import org.z2six.villageroverhaul.server.VillagerStatsService;
import org.z2six.villageroverhaul.server.RecruitService;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;
import org.z2six.villageroverhaul.server.ai.VillagerCombatLoadoutService;
import org.z2six.villageroverhaul.server.ai.VillagerEatTestService;
import org.z2six.villageroverhaul.server.FarmingSettingsService;
import org.z2six.villageroverhaul.server.CustomCommandsService;
import org.z2six.villageroverhaul.server.PlayerFarmingProfilesSavedData;
import org.z2six.villageroverhaul.server.PlayerChatCommandsSavedData;
import org.z2six.villageroverhaul.server.TradingHallService;
import org.z2six.villageroverhaul.combat.CombatSettings;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

// patrol packets

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

public final class ServerHandlers {

    private ServerHandlers() {}

    private static void playVillagerSound(Villager vill, SoundEvent sound, float volume, float pitch) {
        try {
            if (vill == null || sound == null) return;
            var level = vill.level();
            if (level == null) return;
            level.playSound(null, vill.blockPosition(), sound, SoundSource.NEUTRAL, volume, pitch);
        } catch (Throwable ignored) {}
    }

    public static void handleAutoTradeStart(PacketAutoTradeStart msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!ServerConfig.enableMerchantModule) return;

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] start_req player={} containerId={} offerIdx={}",
                    sp.getGameProfile().getName(), msg.containerId(), msg.offerIndex());

            AutoTradeServerService.start(sp, msg.containerId(), msg.offerIndex());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleAutoTradeStart failed", t);
        }
    }

    public static void handleAutoTradeStop(PacketAutoTradeStop msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!ServerConfig.enableMerchantModule) return;

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [autotrade] stop_req player={} containerId={}",
                    sp.getGameProfile().getName(), msg.containerId());

            AutoTradeServerService.stop(sp, msg.containerId(), "client_stop");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleAutoTradeStop failed", t);
        }
    }

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
            if (!ServerConfig.enableMerchantModule) return;

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
            if (!ServerConfig.enableMerchantModule) return;

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
            if (!ServerConfig.enableMerchantModule) return;

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

            // Snapshot or clear the locked offer itself (mask alone is not sufficient for robust locks).
            try {
                boolean nowLocked = idx >= 0 && idx < 63 && (sanitized & (1L << idx)) != 0L;
                if (nowLocked) {
                    TradeLockState.captureLockedOffer(vill, idx);
                } else {
                    TradeLockState.clearLockedOfferSnapshot(vill, idx);
                }
                TradeLockState.sanitizeLockedOfferSnapshots(vill, sanitized, vill.getOffers() == null ? 0 : vill.getOffers().size());
            } catch (Throwable ignored) {}

            try { org.z2six.villageroverhaul.server.VillagerHistoryService.addTradeLockToggle(vill, 1); } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [lock] toggled player={} villager={} idx={} mask={}",
                    sp.getGameProfile().getName(), vill.getUUID(), idx, Long.toUnsignedString(sanitized));

            ctx.reply(new PacketTradeLocks(menu.containerId, sanitized));
            ctx.reply(org.z2six.villageroverhaul.server.TooltipService.computeSnapshot(sp, vill.getId()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleToggleTradeLock failed", t);
        }
    }

    public static void handleSearchCatalogQuery(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!ServerConfig.enableMerchantModule) {
                ctx.reply(PacketSearchCatalogData.minimal(msg.villagerEntityId(), List.of()));
                return;
            }

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

            int freeOffers = Math.max(0, ServerConfig.freeOffers);
            int unlockedOffers = Math.max(0, offerCount - lockedCount);
            int effectivePaidOffers = Math.max(0, unlockedOffers - freeOffers);

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
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handleSearchCatalogQuery snapshot: villager={} offers={} locked={} unlocked={} free={} paid={} manual={} hourly={}",
                        vill.getUUID(),
                        offerCount,
                        lockedCount,
                        unlockedOffers,
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
                    lockMask,
                    effectivePaidOffers,
                    manualCost,
                    hourlyCost,
                    org.z2six.villageroverhaul.server.PlayerAutoSearchCostService.buildValuesForCatalog(sp.server, sp.getUUID(), items)
            ));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleSearchCatalogQuery failed", t);
        }
    }

    public static void handleStartAutoSearch(PacketStartAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!ServerConfig.enableMerchantModule) return;

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
            if (!ServerConfig.enableMerchantModule) return;

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
            if (!ServerConfig.enableMerchantModule) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            SearchService.Settlement settlement = SearchService.getSettlement(vill);
            if (settlement == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] handlePayAutoSearchSettlement: no settlement (villagerId={} uuid={})",
                        vill.getId(), vill.getUUID());
                return;
            }

            int offersNowBefore = -1;
            int levelBefore = -1;
            long lockMaskNow = 0L;
            try { offersNowBefore = (vill.getOffers() == null ? -1 : vill.getOffers().size()); } catch (Throwable ignored) {}
            try { levelBefore = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            try { lockMaskNow = TradeLockState.getMask(vill); } catch (Throwable ignored) { lockMaskNow = 0L; }
            int offersBeforeSnapshot = -1;
            try { offersBeforeSnapshot = SearchService.getSettlementOffersBeforeTag(vill) == null ? -1 : SearchService.getSettlementOffersBeforeTag(vill).size(); } catch (Throwable ignored) {}
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [auto_search] pay_clicked player={} villagerId={} uuid={} cost={} offersLive={} offersBeforeSnap={} levelBefore={} lockMaskNow={} lockMaskBefore={}",
                    sp.getGameProfile().getName(),
                    vill.getId(),
                    vill.getUUID(),
                    SearchService.getSettlementFinalCost(vill),
                    offersNowBefore,
                    offersBeforeSnapshot,
                    levelBefore,
                    Long.toUnsignedString(lockMaskNow),
                    Long.toUnsignedString(SearchService.getSettlementLockMaskBefore(vill))
            );

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
                try {
                    ctx.reply(new org.z2six.villageroverhaul.network.autoReroll.PacketAutoSearchPaymentFailed(vill.getId(), "not_enough_currency"));
                } catch (Throwable ignored) {}
                return;
            }

            try {
                if (cost > 0) {
                    // Track emeralds only when the configured currency is exactly emerald.
                    boolean specIsTag = ServerConfig.isTagSpec(ServerConfig.costSpec);
                    ResourceLocation itemId = specIsTag ? null : ResourceLocation.tryParse(ServerConfig.costSpec);
                    if (!specIsTag && itemId != null && "minecraft:emerald".equals(itemId.toString())) {
                        org.z2six.villageroverhaul.server.VillagerHistoryService.addEmeraldsFromAutoRerolls(vill, cost);
                    }
                }
            } catch (Throwable ignored) {}

            int awardedXp = 0;
            try {
                awardedXp = SearchService.awardSettlementVillagerXpIfAny(vill, settlement);
            } catch (Throwable xpErr) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePayAutoSearchSettlement: awarding XP failed (soft) villager={}", vill.getUUID(), xpErr);
                awardedXp = 0;
            }

            // Clear player-global per-item V debt for the requested targets (so cancel/restart can't avoid it).
            try {
                org.z2six.villageroverhaul.server.PlayerAutoSearchCostService.clearValuesForKeys(
                        sp.server,
                        sp.getUUID(),
                        org.z2six.villageroverhaul.server.SearchService.getSettlementRequestedTargets(vill)
                );
            } catch (Throwable ignored) {}

            SearchService.popSettlementAndClearVisuals(vill, sp.server);

            // Safety: ensure locked trades remain exactly as locked even after auto-search / settlement.
            try {
                org.z2six.villageroverhaul.logic.TradeLockState.ensureSnapshotsForLockedMask(vill);
                org.z2six.villageroverhaul.logic.TradeLockState.restoreLockedOffersFromSnapshots(vill, vill.getOffers());
                org.z2six.villageroverhaul.logic.TradeLockState.sanitizeLockedOfferSnapshots(
                        vill,
                        org.z2six.villageroverhaul.logic.TradeLockState.sanitizeMaskForSize(
                                org.z2six.villageroverhaul.logic.TradeLockState.getMask(vill),
                                safeOfferSize(vill)
                        ),
                        safeOfferSize(vill)
                );
            } catch (Throwable ignored) {}

            int offersNowAfter = -1;
            int levelAfter = -1;
            long lockMaskAfter = 0L;
            try { offersNowAfter = (vill.getOffers() == null ? -1 : vill.getOffers().size()); } catch (Throwable ignored) {}
            try { levelAfter = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}
            try { lockMaskAfter = TradeLockState.getMask(vill); } catch (Throwable ignored) { lockMaskAfter = 0L; }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [auto_search] pay_success player={} villager={} cost={} awardedXp={} settlementXp={} offers {}->{} level {}->{} lockMask {}->{}",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    cost,
                    awardedXp,
                    settlementXp,
                    offersNowBefore,
                    offersNowAfter,
                    levelBefore,
                    levelAfter,
                    Long.toUnsignedString(lockMaskNow),
                    Long.toUnsignedString(lockMaskAfter)
            );

            // Keep client UI lock outlines in sync even if the MerchantScreen instance did not re-init.
            try {
                long sanitized = TradeLockState.sanitizeMaskForSize(lockMaskAfter, safeOfferSize(vill));
                org.z2six.villageroverhaul.server.TradeLockSyncService.syncToActiveTraders(vill, sanitized);
            } catch (Throwable ignored) {}

            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handlePayAutoSearchSettlement failed", t);
        }
    }

    public static void handleDeclineAutoSearchSettlement(PacketDeclineAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!ServerConfig.enableMerchantModule) return;

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

            // Safety: restore exact locked offers (mask alone is not sufficient).
            try {
                org.z2six.villageroverhaul.logic.TradeLockState.ensureSnapshotsForLockedMask(vill);
                org.z2six.villageroverhaul.logic.TradeLockState.restoreLockedOffersFromSnapshots(vill, vill.getOffers());
                org.z2six.villageroverhaul.logic.TradeLockState.sanitizeLockedOfferSnapshots(vill, sanitized, safeOfferSize(vill));
            } catch (Throwable ignored) {}

            SearchService.popSettlementAndClearVisuals(vill, sp.server);
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

            // If a vanilla trade interaction sneaks in, ensure we exit trading so PATROL_SETUP follow can continue.
            try { vill.setTradingPlayer(null); } catch (Throwable ignored) {}

            switch (msg.action()) {
                case ADD_WAYPOINT -> {
                    // Use the player's current position (normalized server-side) rather than the villager's.
                    VillagerBrain.addPatrolWaypointAtPos(vill, sp.position());

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

            if (canOpen) {
                // Clear vanilla trade state if it got set by the interaction.
                try { vill.setTradingPlayer(null); } catch (Throwable ignored) {}
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

            if (!ServerConfig.enableCombatModule) {
                ctx.reply(new PacketVillagerCombatModeData(msg.villagerEntityId(), "off"));
                return;
            }

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                ctx.reply(new PacketVillagerCombatModeData(msg.villagerEntityId(), "off"));
                return;
            }

            var mode = VillagerBrain.getCombatMode(vill);
            ctx.reply(new PacketVillagerCombatModeData(vill.getId(), mode == null ? "off" : mode.id));

        } catch (Throwable ignored) {}
    }

    public static void handleVillagerManualFarmingModeQuery(PacketVillagerManualFarmingModeQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) {
                ctx.reply(new PacketVillagerManualFarmingModeData(msg.villagerEntityId(), false));
                return;
            }

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                ctx.reply(new PacketVillagerManualFarmingModeData(msg.villagerEntityId(), false));
                return;
            }

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                ctx.reply(new PacketVillagerManualFarmingModeData(vill.getId(), false));
                return;
            }
            if (!RecruitService.isRecruited(vill)) {
                ctx.reply(new PacketVillagerManualFarmingModeData(vill.getId(), false));
                return;
            }

            boolean enabled = VillagerBrain.isManualFarmingActive(vill);
            ctx.reply(new PacketVillagerManualFarmingModeData(vill.getId(), enabled));

        } catch (Throwable ignored) {}
    }

    public static void handleVillagerManualFarmingModeCommand(PacketVillagerManualFarmingModeCommand msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) return;

            VillagerBrain.ensureAttached(vill);

            boolean enable = msg.enabled();
            if (enable) {
                // Eligibility gate: manual farming requires a workstation + Farmer profession.
                boolean ok = true;
                try {
                    if (vill.getVillagerData() == null || vill.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.FARMER) ok = false;
                } catch (Throwable ignored) {
                    ok = false;
                }
                try {
                    if (ok && sp.serverLevel() != null && FarmingSettingsService.getEffectiveWorkstation(sp.serverLevel(), vill) == null) ok = false;
                } catch (Throwable ignored) {
                    ok = false;
                }

                if (!ok) {
                    try { ctx.reply(new PacketFarmingOverlayText("Manual farming requires Farmer + workstation", 3200)); } catch (Throwable ignored) {}
                    ctx.reply(new PacketVillagerManualFarmingModeData(vill.getId(), false));
                    return;
                }

                // Remember previous movement mode and temporarily clear movement behavior/highlight.
                VillagerBrain.rememberPrevModeForManualFarming(vill);
                VillagerBrain.setMode(vill, VillagerBrain.Mode.NEUTRAL);
                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}
            } else {
                // Restore previous movement mode after turning manual farming off.
                VillagerBrain.restorePrevModeAfterManualFarming(vill);
            }

            VillagerBrain.setManualFarmingActive(vill, enable);

            ctx.reply(new PacketVillagerManualFarmingModeData(vill.getId(), enable));
            try {
                var mode = VillagerBrain.getMode(vill);
                ctx.reply(new PacketVillagerModeData(vill.getId(), mode == null ? "neutral" : mode.id));
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static boolean tryChargePlayer(ServerPlayer sp, int cost) {
        try {
            return org.z2six.villageroverhaul.logic.PaymentUtil.tryCharge(sp, cost);

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

            // Any movement command should stop manual farming.
            if (VillagerBrain.isManualFarmingActive(vill)) {
                try {
                    VillagerBrain.setManualFarmingActive(vill, false);
                    VillagerBrain.clearPrevModeForManualFarming(vill);
                    ctx.reply(new PacketVillagerManualFarmingModeData(vill.getId(), false));
                } catch (Throwable ignored) {}
            }

            switch (msg.command()) {
                case IDLE -> VillagerBrain.idle(vill);
                case NEUTRAL -> VillagerBrain.neutral(vill);
                case FOLLOW -> org.z2six.villageroverhaul.server.ai.VillagerBrain.follow(vill, sp);
                case TRADING -> VillagerBrain.trading(vill);
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
            if (!ServerConfig.enableCombatModule) return;

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
            if (!ServerConfig.enableCombatModule) {
                ctx.reply(new PacketCombatSettingsData(msg.villagerEntityId(), msg.global(), new net.minecraft.nbt.CompoundTag()));
                return;
            }

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
            if (!ServerConfig.enableCombatModule) return;

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
            if (!ServerConfig.enableCombatModule) return;

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
            if (!ServerConfig.enableCombatModule) return;

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
            if (!ServerConfig.enableCombatModule) return;

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
    // Farming/storage
    // =====================

    public static void handleFarmingSettingsQuery(PacketFarmingSettingsQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) {
                ctx.reply(new PacketFarmingSettingsData(msg.villagerEntityId(), new net.minecraft.nbt.CompoundTag()));
                return;
            }

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                ctx.reply(new PacketFarmingSettingsData(msg.villagerEntityId(), new net.minecraft.nbt.CompoundTag()));
                return;
            }

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) {
                ctx.reply(new PacketFarmingSettingsData(msg.villagerEntityId(), new net.minecraft.nbt.CompoundTag()));
                return;
            }

            var settings = FarmingSettingsService.getSettings(vill);
            try {
                settings.manualWorkstationRegistered = FarmingSettingsService.hasManualWorkstationOverride(vill);
            } catch (Throwable ignored) {}
            net.minecraft.nbt.CompoundTag tag = settings.toTag();
            try { tag.putBoolean("__hasDepositChest", FarmingSettingsService.hasRegisteredChest(vill)); } catch (Throwable ignored) {}
            try { tag.putBoolean("__hasWithdrawChest", FarmingSettingsService.hasRegisteredWithdrawChest(vill)); } catch (Throwable ignored) {}
            ctx.reply(new PacketFarmingSettingsData(vill.getId(), tag));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleFarmingSettingsQuery failed", t);
        }
    }

    public static void handleFarmingSettingsUpdate(PacketFarmingSettingsUpdate msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            var settings = org.z2six.villageroverhaul.farming.FarmingSettings.fromTag(msg.settings());
            try {
                // Server is source-of-truth for ordering; this prevents stale query replies overwriting new updates client-side.
                settings.updatedAt = vill.level() == null ? 0L : Math.max(0L, vill.level().getGameTime());
            } catch (Throwable ignored) {
                settings.updatedAt = 0L;
            }
            try {
                var dep = FarmingSettingsService.sanitizeRules(settings.depositRules);
                settings.depositRules.clear();
                settings.depositRules.addAll(dep);

                var wd = FarmingSettingsService.sanitizeRules(settings.withdrawRules);
                settings.withdrawRules.clear();
                settings.withdrawRules.addAll(wd);
            } catch (Throwable ignored) {}

            try {
                var mh = FarmingSettingsService.sanitizeItemIds(settings.manualHarvestItemIds);
                settings.manualHarvestItemIds.clear();
                settings.manualHarvestItemIds.addAll(mh);

                var mp = FarmingSettingsService.sanitizeItemIds(settings.manualPlantItemIds);
                settings.manualPlantItemIds.clear();
                settings.manualPlantItemIds.addAll(mp);

                var pr = FarmingSettingsService.sanitizeItemIds(settings.pickupItemIds);
                settings.pickupItemIds.clear();
                settings.pickupItemIds.addAll(pr);

                settings.manualRange = Math.max(1, Math.min(64, settings.manualRange));
            } catch (Throwable ignored) {}

            // Server-authoritative clamp: player can set a smaller range, but never above villager's max (Ranger + config).
            try {
                int maxAllowed = FarmingSettingsService.getMaxManualFarmingRange(vill);
                if (settings.manualRange > maxAllowed) settings.manualRange = maxAllowed;
                if (settings.manualRange < 1) settings.manualRange = 1;
                if (settings.manualRange > 64) settings.manualRange = 64;
            } catch (Throwable ignored) {}

            // Derived / server-owned
            try {
                settings.manualWorkstationRegistered = FarmingSettingsService.hasManualWorkstationOverride(vill);
            } catch (Throwable ignored) {}
            FarmingSettingsService.setSettings(vill, settings);

            net.minecraft.nbt.CompoundTag tag = settings.toTag();
            try { tag.putBoolean("__hasDepositChest", FarmingSettingsService.hasRegisteredChest(vill)); } catch (Throwable ignored) {}
            try { tag.putBoolean("__hasWithdrawChest", FarmingSettingsService.hasRegisteredWithdrawChest(vill)); } catch (Throwable ignored) {}
            ctx.reply(new PacketFarmingSettingsData(vill.getId(), tag));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleFarmingSettingsUpdate failed", t);
        }
    }

    public static void handleRegisterFarmingChest(PacketRegisterFarmingChest msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) {
                try { ctx.reply(new PacketFarmingOverlayText("Villager is not recruited", 2200)); } catch (Throwable ignored) {}
                return;
            }

            var pos = msg.pos();
            var level = sp.serverLevel();
            if (level == null) return;

            var state = level.getBlockState(pos);
            boolean isChest = false;
            boolean isEnder = false;
            try {
                if (state != null) {
                    var b = state.getBlock();
                    isEnder = (b == net.minecraft.world.level.block.Blocks.ENDER_CHEST);
                    isChest = isEnder || b == net.minecraft.world.level.block.Blocks.CHEST || b == net.minecraft.world.level.block.Blocks.TRAPPED_CHEST;
                }
            } catch (Throwable ignored) {
                isChest = false;
                isEnder = false;
            }

            if (!isChest) {
                try { ctx.reply(new PacketFarmingOverlayText("Not a chest", 2200)); } catch (Throwable ignored) {}
                return;
            }

            // Must have a workstation to define a farming area.
            if (FarmingSettingsService.getEffectiveWorkstation(level, vill) == null) {
                try { ctx.reply(new PacketFarmingOverlayText("Villager has no workstation", 2200)); } catch (Throwable ignored) {}
                return;
            }

            var settings = FarmingSettingsService.getSettings(vill);
            boolean circular = settings != null && settings.manualRangeCircular;
            if (!FarmingSettingsService.isWithinManualFarmingArea(level, vill, pos, circular)) {
                try { ctx.reply(new PacketFarmingOverlayText("Chest is not within villager range", 2600)); } catch (Throwable ignored) {}
                return;
            }

            String dim = "";
            try {
                dim = String.valueOf(level.dimension().location());
            } catch (Throwable ignored) {
                dim = "";
            }

            FarmingSettingsService.setRegisteredChest(vill, dim, pos.getX(), pos.getY(), pos.getZ(), isEnder);
            try {
                var st = FarmingSettingsService.getSettings(vill);
                st.manualWorkstationRegistered = FarmingSettingsService.hasManualWorkstationOverride(vill);
                net.minecraft.nbt.CompoundTag tag = st.toTag();
                try { tag.putBoolean("__hasDepositChest", true); } catch (Throwable ignored) {}
                try { tag.putBoolean("__hasWithdrawChest", FarmingSettingsService.hasRegisteredWithdrawChest(vill)); } catch (Throwable ignored) {}
                ctx.reply(new PacketFarmingSettingsData(vill.getId(), tag));
            } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Deposit chest registered", 2200)); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleRegisterFarmingChest failed", t);
        }
    }

    public static void handleRegisterFarmingWithdrawChest(PacketRegisterFarmingWithdrawChest msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) {
                try { ctx.reply(new PacketFarmingOverlayText("Villager is not recruited", 2200)); } catch (Throwable ignored) {}
                return;
            }

            var pos = msg.pos();
            var level = sp.serverLevel();
            if (level == null) return;

            var state = level.getBlockState(pos);
            boolean isChest = false;
            boolean isEnder = false;
            try {
                if (state != null) {
                    var b = state.getBlock();
                    isEnder = (b == net.minecraft.world.level.block.Blocks.ENDER_CHEST);
                    isChest = isEnder || b == net.minecraft.world.level.block.Blocks.CHEST || b == net.minecraft.world.level.block.Blocks.TRAPPED_CHEST;
                }
            } catch (Throwable ignored) {
                isChest = false;
                isEnder = false;
            }

            if (!isChest) {
                try { ctx.reply(new PacketFarmingOverlayText("Not a chest", 2200)); } catch (Throwable ignored) {}
                return;
            }

            // Must have a workstation to define a farming area.
            if (FarmingSettingsService.getEffectiveWorkstation(level, vill) == null) {
                try { ctx.reply(new PacketFarmingOverlayText("Villager has no workstation", 2200)); } catch (Throwable ignored) {}
                return;
            }

            var settings = FarmingSettingsService.getSettings(vill);
            boolean circular = settings != null && settings.manualRangeCircular;
            if (!FarmingSettingsService.isWithinManualFarmingArea(level, vill, pos, circular)) {
                try { ctx.reply(new PacketFarmingOverlayText("Chest is not within villager range", 2600)); } catch (Throwable ignored) {}
                return;
            }

            String dim = "";
            try {
                dim = String.valueOf(level.dimension().location());
            } catch (Throwable ignored) {
                dim = "";
            }

            FarmingSettingsService.setRegisteredWithdrawChest(vill, dim, pos.getX(), pos.getY(), pos.getZ(), isEnder);
            try {
                var st = FarmingSettingsService.getSettings(vill);
                st.manualWorkstationRegistered = FarmingSettingsService.hasManualWorkstationOverride(vill);
                net.minecraft.nbt.CompoundTag tag = st.toTag();
                try { tag.putBoolean("__hasDepositChest", FarmingSettingsService.hasRegisteredChest(vill)); } catch (Throwable ignored) {}
                try { tag.putBoolean("__hasWithdrawChest", true); } catch (Throwable ignored) {}
                ctx.reply(new PacketFarmingSettingsData(vill.getId(), tag));
            } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Withdraw chest registered", 2200)); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleRegisterFarmingWithdrawChest failed", t);
        }
    }

    public static void handleRegisterFarmingWorkstation(PacketRegisterFarmingWorkstation msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!org.z2six.villageroverhaul.config.ServerConfig.enableFarmingModule) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) {
                try { ctx.reply(new PacketFarmingOverlayText("Villager is not recruited", 2200)); } catch (Throwable ignored) {}
                return;
            }

            var pos = msg.pos();
            var level = sp.serverLevel();
            if (level == null) return;
            if (pos == null) return;

            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
            FarmingSettingsService.setRegisteredWorkstation(vill, dim, pos.getX(), pos.getY(), pos.getZ());
            try {
                var settings = FarmingSettingsService.getSettings(vill);
                settings.manualWorkstationRegistered = true;
                FarmingSettingsService.setSettings(vill, settings);
                net.minecraft.nbt.CompoundTag tag = settings.toTag();
                try { tag.putBoolean("__hasDepositChest", FarmingSettingsService.hasRegisteredChest(vill)); } catch (Throwable ignored) {}
                try { tag.putBoolean("__hasWithdrawChest", FarmingSettingsService.hasRegisteredWithdrawChest(vill)); } catch (Throwable ignored) {}
                ctx.reply(new PacketFarmingSettingsData(vill.getId(), tag));
            } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Workstation registered", 2200)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleRegisterTradingHall(PacketRegisterTradingHall msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;
            if (!RecruitService.isRecruited(vill)) {
                try { ctx.reply(new PacketFarmingOverlayText("Villager is not recruited", 2200)); } catch (Throwable ignored) {}
                return;
            }

            ServerLevel level = sp.serverLevel();
            if (level == null) return;

            var pos = msg.pos();
            if (pos == null) return;
            if (!level.getBlockState(pos).is(org.z2six.villageroverhaul.content.ModBlocks.TRADING_HALL.get())) {
                try { ctx.reply(new PacketFarmingOverlayText("Not a Trading Hall", 2200)); } catch (Throwable ignored) {}
                return;
            }
            if (!TradingHallService.isWithinReasonableDistance(level, vill, pos)) {
                try {
                    ctx.reply(new PacketFarmingOverlayText(
                            "Trading Hall must be within " + TradingHallService.MAX_HALL_DISTANCE_BLOCKS + " blocks of the villager workstation",
                            2800
                    ));
                } catch (Throwable ignored) {}
                return;
            }

            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }
            TradingHallService.setRegisteredHall(vill, dim, pos.getX(), pos.getY(), pos.getZ());

            try { ctx.reply(new PacketFarmingOverlayText("Trading Hall registered", 2200)); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleRegisterTradingHall failed", t);
        }
    }

    // =============================
    // CUSTOM COMMANDS (CC)
    // =============================

    public static void handleCcBeginTeaching(PacketCcBeginTeaching msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.beginTeaching(sp, vill, msg.editIndex());
            playVillagerSound(vill, SoundEvents.VILLAGER_AMBIENT, 0.8f, 1.0f);

            try { ctx.reply(new PacketCcWaitState(vill.getId(), false)); } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Teach the Villager what to do...", 1000000)); } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] handleCcBeginTeaching failed", t);
        }
    }

    public static void handleCcStopTeaching(PacketCcStopTeaching msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                // Best-effort cleanup even if the villager entity isn't currently resolvable (chunk unload / dimension change).
                try { CustomCommandsService.stopTeaching(sp); } catch (Throwable ignored) {}
                try { ctx.reply(new PacketCcWaitState(msg.villagerEntityId(), false)); } catch (Throwable ignored) {}
                try { ctx.reply(new PacketFarmingOverlayText("Teaching canceled", 1800)); } catch (Throwable ignored) {}
                return;
            }

            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.stopTeaching(sp, vill);
            playVillagerSound(vill, SoundEvents.VILLAGER_NO, 1.0f, 0.95f);

            try { ctx.reply(new PacketCcWaitState(vill.getId(), false)); } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Teaching canceled", 1800)); } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public static void handleCcAddWaypoint(PacketCcAddWaypoint msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.addWaypointStep(sp, vill);
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);
            try { ctx.reply(new PacketFarmingOverlayText("Waypoint added", 1200)); } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public static void handleCcAddWaitStep(PacketCcAddWaitStep msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.addWaitStep(sp, vill, msg.seconds());
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);
            try { ctx.reply(new PacketFarmingOverlayText("Recorded. Teach the Villager what to do...", 1000000)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcBeginRecord(PacketCcBeginRecord msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            int kind = msg.kind();
            if (kind < 0 || kind > 3) kind = 0;

            CustomCommandsService.setWaiting(sp, vill, kind);
            playVillagerSound(vill, SoundEvents.VILLAGER_AMBIENT, 0.8f, 1.0f);
            try { ctx.reply(new PacketCcWaitState(vill.getId(), true)); } catch (Throwable ignored) {}

            // Overlay text for kind=3 (look) is handled client-side (color fade / timer).
            if (kind != 3) {
                String text = "Show the Villager what to interact with or press ESC to cancel";
                if (kind == 1) text = "Show the Villager what chest to take items from or press ESC to cancel";
                if (kind == 2) text = "Show the Villager what chest to deposit items into or press ESC to cancel";
                try { ctx.reply(new PacketFarmingOverlayText(text, 1000000)); } catch (Throwable ignored) {}
            }

        } catch (Throwable ignored) {}
    }

    public static void handleCcRecordLook(PacketCcRecordLook msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            boolean ok = false;
            try { ok = CustomCommandsService.recordLook(sp, vill, msg.yaw(), msg.pitch()); } catch (Throwable ignored) { ok = false; }
            if (!ok) return;

            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);
            try { ctx.reply(new PacketCcWaitState(vill.getId(), false)); } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Recorded. Teach the Villager what to do...", 1000000)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcSetLookDuration(PacketCcSetLookDuration msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            boolean ok = false;
            try { ok = CustomCommandsService.setSessionLookDuration(sp, vill, msg.stepIndex(), msg.seconds()); } catch (Throwable ignored) { ok = false; }
            if (!ok) return;

            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.15f);
            try { ctx.reply(new PacketFarmingOverlayText("Saved. Teach the Villager what to do...", 1000000)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcCancelRecord(PacketCcCancelRecord msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.setWaiting(sp, vill, -1);
            playVillagerSound(vill, SoundEvents.VILLAGER_NO, 1.0f, 0.95f);
            try { ctx.reply(new PacketCcWaitState(vill.getId(), false)); } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Recording canceled", 1800)); } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Teach the Villager what to do...", 1000000)); } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public static void handleCcTeachSessionQuery(PacketCcTeachSessionQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            var tag = CustomCommandsService.buildTeachSessionData(sp, vill);
            ctx.reply(new PacketCcTeachSessionData(vill.getId(), tag));
        } catch (Throwable ignored) {}
    }

    public static void handleCcSaveTaughtAction(PacketCcSaveTaughtAction msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.saveFromSession(
                    sp,
                    vill,
                    msg.title(),
                    msg.command(),
                    msg.caseSensitive(),
                    msg.chain(),
                    msg.anyone(),
                    msg.description(),
                    msg.timeoutSeconds(),
                    msg.retryAfterSeconds(),
                    msg.stopAfterRetries(),
                    msg.editIndex()
            );
            CustomCommandsService.stopTeaching(sp, vill);
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.2f);

            try { ctx.reply(new PacketCcWaitState(vill.getId(), false)); } catch (Throwable ignored) {}
            try { ctx.reply(new PacketFarmingOverlayText("Saved", 1600)); } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public static void handleCcUpdateActionMeta(PacketCcUpdateActionMeta msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.updateActionMeta(
                    vill,
                    msg.actionIndex(),
                    msg.title(),
                    msg.command(),
                    msg.caseSensitive(),
                    msg.chain(),
                    msg.anyone(),
                    msg.description(),
                    msg.timeoutSeconds(),
                    msg.retryAfterSeconds(),
                    msg.stopAfterRetries()
            );
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);

            try { ctx.reply(new PacketFarmingOverlayText("Saved", 1600)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcDeleteAction(PacketCcDeleteAction msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.deleteAction(vill, msg.actionIndex());
            playVillagerSound(vill, SoundEvents.VILLAGER_NO, 1.0f, 1.0f);
            try { ctx.reply(new PacketFarmingOverlayText("Forgot teaching", 1600)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcUpdateActionStepWait(PacketCcUpdateActionStepWait msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.updateActionStepWait(vill, msg.actionIndex(), msg.stepIndex(), msg.seconds());
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);
            try { ctx.reply(new PacketFarmingOverlayText("Saved", 1200)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcUpdateActionStepLookDuration(PacketCcUpdateActionStepLookDuration msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.updateActionStepLookDuration(vill, msg.actionIndex(), msg.stepIndex(), msg.seconds());
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);
            try { ctx.reply(new PacketFarmingOverlayText("Saved", 1200)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcUpdateActionStepRules(PacketCcUpdateActionStepRules msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            java.util.List<CustomCommandsService.ItemCountRule> rules = new java.util.ArrayList<>();
            if (msg.rules() != null) {
                for (PacketCcUpdateActionStepRules.Rule r : msg.rules()) {
                    if (r == null) continue;
                    String id = r.itemId() == null ? "" : r.itemId().trim();
                    if (id.isBlank()) continue;
                    int cnt = Math.max(0, r.count());
                    rules.add(new CustomCommandsService.ItemCountRule(id, cnt));
                }
            }
            CustomCommandsService.updateActionStepRules(vill, msg.actionIndex(), msg.stepIndex(), rules);
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);
            try { ctx.reply(new PacketFarmingOverlayText("Saved", 1200)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcSetChestRules(PacketCcSetChestRules msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            var session = CustomCommandsService.getSessionFor(sp, vill);
            if (session == null) return;

            int stepIndex = msg.stepIndex();
            java.util.List<CustomCommandsService.ItemCountRule> rules = new java.util.ArrayList<>();
            if (msg.rules() != null) {
                for (PacketCcSetChestRules.Rule r : msg.rules()) {
                    if (r == null) continue;
                    String id = r.itemId() == null ? "" : r.itemId().trim();
                    if (id.isBlank()) continue;
                    int cnt = Math.max(0, r.count());
                    rules.add(new CustomCommandsService.ItemCountRule(id, cnt));
                }
            }
            CustomCommandsService.setSessionChestRules(sp, vill, stepIndex, rules);
            playVillagerSound(vill, SoundEvents.VILLAGER_YES, 1.0f, 1.1f);

            try { ctx.reply(new PacketFarmingOverlayText("Recorded. Teach the Villager what to do...", 1000000)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void handleCcListQuery(PacketCcListQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            ctx.reply(new PacketCcListData(vill.getId(), CustomCommandsService.buildListData(vill)));
        } catch (Throwable ignored) {}
    }

    public static void handleCcActionDetailQuery(PacketCcActionDetailQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            ctx.reply(new PacketCcActionDetailData(vill.getId(), msg.index(), CustomCommandsService.buildActionDetailData(vill, msg.index())));
        } catch (Throwable ignored) {}
    }

    // =====================
    // Custom Commands: per-villager chat listen toggle
    // =====================

    public static void handleCcChatListenQuery(PacketCcChatListenQuery msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            ctx.reply(new PacketCcChatListenData(
                    vill.getId(),
                    CustomCommandsService.isChatListening(vill),
                    CustomCommandsService.isChatPassing(vill),
                    CustomCommandsService.getChatPassRange(vill)
            ));
        } catch (Throwable ignored) {}
    }

    public static void handleCcChatListenSet(PacketCcChatListenSet msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            CustomCommandsService.setChatListening(vill, msg.listen());
            CustomCommandsService.setChatPassing(vill, msg.pass(), msg.passRange());
            ctx.reply(new PacketCcChatListenData(
                    vill.getId(),
                    CustomCommandsService.isChatListening(vill),
                    CustomCommandsService.isChatPassing(vill),
                    CustomCommandsService.getChatPassRange(vill)
            ));
        } catch (Throwable ignored) {}
    }

    public static void handleCcSetCombatOverride(PacketCcSetCombatOverride msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!org.z2six.villageroverhaul.server.VillagerAccessGate.canUseControls(vill, sp)) return;

            int idx = msg.actionIndex();
            if (idx < 0) return;
            CustomCommandsService.setCombatOverride(vill, idx, msg.enabled());
            playVillagerSound(vill, SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, msg.enabled() ? 1.25f : 0.9f);
        } catch (Throwable ignored) {}
    }

    // =====================
    // Player Chat Commands (per-player settings)
    // =====================

    public static void handlePlayerChatCommandsQuery(PacketPlayerChatCommandsQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PlayerChatCommandsSavedData sd = PlayerChatCommandsSavedData.get(sp.server);
            PlayerChatCommandsSavedData.Config cfg = sd.getOrCreate(sp.getUUID());
            ctx.reply(new PacketPlayerChatCommandsData(PlayerChatCommandsSavedData.toTag(cfg)));
        } catch (Throwable ignored) {}
    }

    public static void handlePlayerChatCommandsUpdate(PacketPlayerChatCommandsUpdate msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PlayerChatCommandsSavedData sd = PlayerChatCommandsSavedData.get(sp.server);
            PlayerChatCommandsSavedData.Config cfg = PlayerChatCommandsSavedData.fromTag(msg.data());

            int max = Math.max(1, ServerConfig.customCommandsChatRadius);
            if (cfg.range < 1) cfg.range = 1;
            if (cfg.range > max) cfg.range = max;

            sd.update(sp.getUUID(), cfg);
            ctx.reply(new PacketPlayerChatCommandsData(PlayerChatCommandsSavedData.toTag(cfg)));
        } catch (Throwable ignored) {}
    }

    // =====================
    // Player Farming Profiles (per-player settings)
    // =====================

    public static void handleFarmingProfilesQuery(PacketFarmingProfilesQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PlayerFarmingProfilesSavedData sd = PlayerFarmingProfilesSavedData.get(sp.server);
            ctx.reply(new PacketFarmingProfilesData(PlayerFarmingProfilesSavedData.toTag(sd.getProfiles(sp.getUUID()))));
        } catch (Throwable ignored) {}
    }

    public static void handleFarmingProfileUpsert(PacketFarmingProfileUpsert msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PlayerFarmingProfilesSavedData sd = PlayerFarmingProfilesSavedData.get(sp.server);
            sd.upsert(sp.getUUID(), msg.name(), org.z2six.villageroverhaul.farming.FarmingSettings.fromTag(msg.settings()));
            ctx.reply(new PacketFarmingProfilesData(PlayerFarmingProfilesSavedData.toTag(sd.getProfiles(sp.getUUID()))));
        } catch (Throwable ignored) {}
    }

    public static void handleFarmingProfileDelete(PacketFarmingProfileDelete msg, IPayloadContext ctx) {
        try {
            if (msg == null) return;
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            PlayerFarmingProfilesSavedData sd = PlayerFarmingProfilesSavedData.get(sp.server);
            sd.delete(sp.getUUID(), msg.name());
            ctx.reply(new PacketFarmingProfilesData(PlayerFarmingProfilesSavedData.toTag(sd.getProfiles(sp.getUUID()))));
        } catch (Throwable ignored) {}
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
                                java.lang.reflect.Method mGet = cache.getClass().getMethod("get", java.util.UUID.class);
                                Object opt = mGet.invoke(cache, rid);
                                if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                                    Object gp = o.get();
                                    java.lang.reflect.Method mName = gp.getClass().getMethod("getName");
                                    Object n = mName.invoke(gp);
                                    if (n instanceof String s) byName = s;
                                }
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
