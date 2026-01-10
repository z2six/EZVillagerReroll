// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
package org.z2six.ezvillagerreroll.network;

import com.mojang.serialization.DataResult;
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
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.logic.CostUtil;
import org.z2six.ezvillagerreroll.logic.MoneyBridge;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;
import org.z2six.ezvillagerreroll.logic.RerollState;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.logic.WalletBridge;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.server.CatalogBuilder;
import org.z2six.ezvillagerreroll.server.SearchService;
import org.z2six.ezvillagerreroll.server.VillagerOffersSavedData;
import org.z2six.ezvillagerreroll.logic.VillagerTraitEffects;
import org.z2six.ezvillagerreroll.server.VillagerStatsService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * Server-side packet handlers.
 *
 * NOTE:
 * This file is a MERGE of the original ServerHandlers +
 * the new auto-search settlement payment logic.
 */
public final class ServerHandlers {

    private ServerHandlers() {}

    // =========================================================================================
    // EXISTING HANDLERS (UNCHANGED)
    // =========================================================================================

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            RerollExecutor.tryReroll(sp);
            sendCooldownStateSnapshot(sp, ctx);
            sendCurrentTradeLocksSnapshot(sp, ctx);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll failed", t);
        }
    }

    public static void handleRerollCooldownQuery(PacketRerollCooldownQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            sendCooldownStateSnapshot(sp, ctx);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleRerollCooldownQuery failed", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            int idx = msg.tradeIndex();
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;

            long next = TradeLockState.toggle(vill, idx);
            long sanitized = TradeLockState.sanitizeMaskForSize(next, vill.getOffers().size());
            TradeLockState.setMask(vill, sanitized);

            ctx.reply(new PacketTradeLocks(menu.containerId, sanitized));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock failed", t);
        }
    }

    public static void handleSearchCatalogQuery(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery: villager not resolved for entityId={} (player={})",
                        msg.villagerEntityId(), sp.getGameProfile().getName());
                ctx.reply(PacketSearchCatalogData.minimal(msg.villagerEntityId(), List.of()));
                return;
            }

            // Build catalog entries (server side)
            List<net.minecraft.world.item.ItemStack> items;
            try {
                items = CatalogBuilder.buildCatalog(vill);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] handleSearchCatalogQuery: CatalogBuilder.buildCatalog failed (villager={})",
                        vill.getUUID(), t);
                items = List.of();
            }

            // ---- Cost preview computation (must match your config semantics) ----
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
                    EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery: sanitized lock mask due to offer size change (villager={} before={} after={} offers={})",
                            vill.getUUID(),
                            Long.toUnsignedString(lockMask),
                            Long.toUnsignedString(sanitized),
                            offerCount);
                    lockMask = sanitized;

                    try {
                        org.z2six.ezvillagerreroll.server.TradeLockSyncService.syncToActiveTraders(vill, lockMask);
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
                EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery: computeHourlyCostServer failed (soft): {}", t.toString());
                hourlyCost = 0;
            }

            if (EZVillagerReroll.LOG().isDebugEnabled()) {
                EZVillagerReroll.LOG().debug("[EZVR] handleSearchCatalogQuery snapshot: villager={} offers={} locked={} deductibleLocks={} free={} paid={} manual={} hourly={}",
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
            EZVillagerReroll.LOG().error("[EZVR] handleSearchCatalogQuery failed", t);
        }
    }

    public static void handleStartAutoSearch(PacketStartAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill != null) {
                SearchService.start(sp, vill, msg.targets());
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleStartAutoSearch failed", t);
        }
    }

    public static void handleCancelAutoSearch(PacketCancelAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            SearchService.cancelByEntityId(sp, msg.villagerEntityId());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleCancelAutoSearch failed", t);
        }
    }

    public static void handleContinueAutoSearch(PacketContinueAutoSearch msg, IPayloadContext ctx) {
        // no-op by design
    }

    // =========================================================================================
    // NEW: AUTO-SEARCH SETTLEMENT HANDLERS
    // =========================================================================================

    public static void handlePayAutoSearchSettlement(PacketPayAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            SearchService.Settlement settlement = SearchService.getSettlement(vill);
            if (settlement == null) {
                EZVillagerReroll.LOG().debug("[EZVR] handlePayAutoSearchSettlement: no settlement (villagerId={} uuid={})",
                        vill.getId(), vill.getUUID());
                return;
            }

            // Read this while settlement is still present in SearchService map
            int settlementXp = 0;
            try {
                settlementXp = Math.max(0, SearchService.getSettlementTotalVillagerXp(vill));
            } catch (Throwable ignored) {
                settlementXp = 0;
            }

            int cost = SearchService.getSettlementFinalCost(vill);
            if (cost > 0 && !tryChargePlayer(sp, cost)) {
                EZVillagerReroll.LOG().debug("[EZVR] handlePayAutoSearchSettlement: charge failed (player={} cost={} villager={})",
                        sp.getGameProfile().getName(), cost, vill.getUUID());
                return;
            }

            // ---------------------------------------------------------------------------------
            // Award villager XP ONLY after payment succeeds.
            // ---------------------------------------------------------------------------------
            int awardedXp = 0;
            try {
                awardedXp = SearchService.awardSettlementVillagerXpIfAny(vill, settlement);
            } catch (Throwable xpErr) {
                EZVillagerReroll.LOG().error("[EZVR] handlePayAutoSearchSettlement: awarding XP failed (soft) villager={}", vill.getUUID(), xpErr);
                awardedXp = 0;
            }

            // Remove settlement after successful payment + XP award attempt
            SearchService.popSettlement(vill.getUUID());

            var data = VillagerOffersSavedData.get(sp.serverLevel());
            if (data != null) {
                data.capture(vill);
                EZVillagerReroll.LOG().debug("[EZVR] handlePayAutoSearchSettlement: captured post-pay offers (villager={})", vill.getUUID());
            } else {
                EZVillagerReroll.LOG().debug("[EZVR] handlePayAutoSearchSettlement: VillagerOffersSavedData missing (villager={})", vill.getUUID());
            }

            EZVillagerReroll.LOG().info("[EZVR] handlePayAutoSearchSettlement: success (player={} villager={} cost={} awardedXp={} settlementXp={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    cost,
                    awardedXp,
                    settlementXp
            );

            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handlePayAutoSearchSettlement failed", t);
        }
    }

    public static void handleDeclineAutoSearchSettlement(PacketDeclineAutoSearchSettlement msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) return;

            Object settlement = SearchService.getSettlement(vill);
            if (settlement == null) {
                EZVillagerReroll.LOG().debug("[EZVR] handleDeclineAutoSearchSettlement: no settlement (villagerId={} uuid={})", vill.getId(), vill.getUUID());
                return;
            }

            // ---------------------------------------------------------------------------------
            // FIX: Prefer the settlement’s own "offersIfDecline" snapshot (server-authoritative),
            // NOT the generic VillagerOffersSavedData, which may have been overwritten by other saves.
            // ---------------------------------------------------------------------------------
            boolean restored = false;

            ListTag offersIfDecline = reflectListTag(settlement, "offersIfDecline");
            if (offersIfDecline != null && !offersIfDecline.isEmpty()) {
                restored = applyOffersFromOfferTagList(vill, offersIfDecline, "settlement.offersIfDecline");
            } else {
                EZVillagerReroll.LOG().debug("[EZVR] handleDeclineAutoSearchSettlement: settlement.offersIfDecline missing/empty (villager={})", vill.getUUID());
            }

            // Restore lock mask from settlement (so lock highlights + cost math revert too)
            Long lockMaskBefore = reflectLong(settlement, "lockMaskBefore");
            if (lockMaskBefore != null) {
                long sanitized = TradeLockState.sanitizeMaskForSize(lockMaskBefore, safeOfferSize(vill));
                TradeLockState.setMask(vill, sanitized);
                EZVillagerReroll.LOG().debug("[EZVR] handleDeclineAutoSearchSettlement: restored lockMaskBefore={} sanitized={} offers={} villager={}",
                        Long.toUnsignedString(lockMaskBefore),
                        Long.toUnsignedString(sanitized),
                        safeOfferSize(vill),
                        vill.getUUID());
                try {
                    org.z2six.ezvillagerreroll.server.TradeLockSyncService.syncToActiveTraders(vill, sanitized);
                } catch (Throwable ignored) {
                    // soft
                }
            } else {
                EZVillagerReroll.LOG().debug("[EZVR] handleDeclineAutoSearchSettlement: settlement.lockMaskBefore missing (villager={})", vill.getUUID());
            }

            // Fallback if settlement snapshot unavailable for any reason
            if (!restored) {
                var data = VillagerOffersSavedData.get(sp.serverLevel());
                if (data != null && data.has(vill.getUUID())) {
                    data.apply(vill);
                    restored = true;
                    EZVillagerReroll.LOG().debug("[EZVR] handleDeclineAutoSearchSettlement: fallback restored via VillagerOffersSavedData (villager={})", vill.getUUID());
                } else {
                    EZVillagerReroll.LOG().warn("[EZVR] handleDeclineAutoSearchSettlement: failed to restore offers (no settlement snapshot, no saveddata) villager={}",
                            vill.getUUID());
                }
            }

            SearchService.popSettlement(vill.getUUID());
            ctx.reply(new PacketAutoSearchSettlementCleared(vill.getId()));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleDeclineAutoSearchSettlement failed", t);
        }
    }

    // =========================================================================================
    // HELPERS
    // =========================================================================================

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
            EZVillagerReroll.LOG().debug("[EZVR] tryChargePlayer failed (soft): {}", t.toString());
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

            // Remaining cooldown (already uses the effective cooldown via RerollState)
            int remaining = RerollState.cooldownRemainingTicks(sp.serverLevel(), vill);

            // Configured cooldown shown to client SHOULD match what the server uses (Timeliness-adjusted)
            int configured = 0;
            try {
                int base = ServerConfig.cooldownTicks;
                if (base > 0) {
                    try { VillagerStatsService.ensureStats(vill); } catch (Throwable ignored) {}

                    double pct = 0.0;
                    try { pct = VillagerTraitEffects.timelinessPct(vill); } catch (Throwable ignored) { pct = 0.0; }

                    configured = VillagerTraitEffects.applyCooldownPercent(base, pct);

                    // cooldown enabled => never allow it to become "0" from modifiers
                    if (configured <= 0) configured = 1;
                } else {
                    configured = 0; // disabled at config level
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

    // -----------------------------------------------------------------------------------------
    // Settlement snapshot decoding (NBT -> MerchantOffer list)
    // We intentionally keep this logic local so we do NOT depend on your TradeUtil internals.
    // It only requires that offersIfDecline/offersIfPay were saved as a ListTag of offer objects
    // encoded via MerchantOffer.CODEC (which is how 1.21.x expects it).
    // -----------------------------------------------------------------------------------------

    // ServerHandlers.java
    private static boolean applyOffersFromOfferTagList(Villager vill, ListTag offerList, String reason) {
        try {
            if (vill == null) return false;
            if (offerList == null || offerList.isEmpty()) return false;

            MerchantOffers decoded = new MerchantOffers();

            int n = Math.min(256, offerList.size());
            int ok = 0;
            int bad = 0;

            for (int i = 0; i < n; i++) {
                final int idx = i; // <-- FIX: capture loop index for lambda

                Tag t = offerList.get(i);
                if (t == null) {
                    bad++;
                    continue;
                }

                DataResult<MerchantOffer> res = MerchantOffer.CODEC.parse(NbtOps.INSTANCE, t);
                Optional<MerchantOffer> opt = res.resultOrPartial(err ->
                        EZVillagerReroll.LOG().debug("[EZVR] applyOffersFromOfferTagList: decode error (villager={} idx={} reason={}): {}",
                                vill.getUUID(), idx, reason, err)
                );

                if (opt.isPresent()) {
                    decoded.add(opt.get());
                    ok++;
                } else {
                    bad++;
                }
            }

            // Apply by mutating the existing MerchantOffers list. This avoids needing accessors and
            // plays nicer with any vanilla code holding a reference to vill.getOffers().
            try {
                MerchantOffers current = vill.getOffers();
                current.clear();
                current.addAll(decoded);
            } catch (Throwable t) {
                // As a fallback, try a best-effort reflection set (rarely needed).
                if (!trySetOffersReflect(vill, decoded)) {
                    EZVillagerReroll.LOG().warn("[EZVR] applyOffersFromOfferTagList: failed to apply offers (villager={} reason={} ok={} bad={})",
                            vill.getUUID(), reason, ok, bad);
                    return false;
                }
            }

            EZVillagerReroll.LOG().debug("[EZVR] applyOffersFromOfferTagList: applied offers (villager={} reason={} count={} ok={} bad={})",
                    vill.getUUID(), reason, decoded.size(), ok, bad);
            return true;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] applyOffersFromOfferTagList failed (reason=" + reason + ")", e);
            return false;
        }
    }

    private static boolean trySetOffersReflect(Villager vill, MerchantOffers offers) {
        try {
            if (vill == null || offers == null) return false;

            // Try common field names across mappings/versions.
            // We do not crash if this fails; we just log and return false.
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
                } catch (NoSuchFieldException ignored) {
                    // try next
                }
            }

            // Walk superclasses in case field is on AbstractVillager
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
                    } catch (NoSuchFieldException ignored) {
                        // try next
                    }
                }
                c = c.getSuperclass();
            }

            EZVillagerReroll.LOG().debug("[EZVR] trySetOffersReflect: could not locate offers field (villager={})", vill.getUUID());
            return false;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] trySetOffersReflect failed (soft): {}", t.toString());
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

    private static ListTag reflectListTag(Object obj, String fieldName) {
        try {
            if (obj == null || fieldName == null) return null;

            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return null;

            Object v = f.get(obj);
            if (v instanceof ListTag lt) return lt;
            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] reflectListTag failed (soft): {}", t.toString());
            return null;
        }
    }

    private static Long reflectLong(Object obj, String fieldName) {
        try {
            if (obj == null || fieldName == null) return null;

            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return null;

            Object v = f.get(obj);
            if (v instanceof Long l) return l;
            if (v instanceof Number n) return n.longValue();
            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] reflectLong failed (soft): {}", t.toString());
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        try {
            if (cls == null || name == null) return null;

            Class<?> c = cls;
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            return null;

        } catch (Throwable t) {
            return null;
        }
    }
}
