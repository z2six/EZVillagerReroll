// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.server.CatalogBuilder;
import org.z2six.ezvillagerreroll.server.SearchService;

import java.util.List;

/**
 * Server-side packet handlers.
 */
public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            EZVillagerReroll.LOG().debug("[EZVR] handleReroll: start (player={})", sp.getGameProfile().getName());
            RerollExecutor.tryReroll(sp);

            try {
                sendCurrentTradeLocksSnapshot(sp, ctx);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().debug("[EZVR] Post-reroll lock snapshot failed (soft): {}", t.toString());
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll failed", t);
        }
    }

    public static void handleToggleTradeLock(PacketToggleTradeLock msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            final int idx;
            try {
                idx = msg.tradeIndex();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] ToggleTradeLock: cannot read tradeIndex() from PacketToggleTradeLock.", t);
                return;
            }

            if (idx < 0 || idx > 63) {
                EZVillagerReroll.LOG().warn("[EZVR] ToggleTradeLock: invalid idx={} (player={})",
                        idx, sp.getGameProfile().getName());
                return;
            }

            if (!(sp.containerMenu instanceof MerchantMenu menu)) {
                EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: player not in MerchantMenu (player={}, idx={})",
                        sp.getGameProfile().getName(), idx);
                return;
            }

            final int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: trader not Villager (player={}, idx={}, trader={})",
                        sp.getGameProfile().getName(), idx, trader == null ? "null" : trader.getClass().getName());

                safeReply(ctx, new PacketTradeLocks(containerId, 0L));
                return;
            }

            long next = TradeLockState.toggle(vill, idx);
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(next, offerSize);
            if (sanitized != next) {
                TradeLockState.setMask(vill, sanitized);
                next = sanitized;
            }

            EZVillagerReroll.LOG().info("[EZVR] ToggleTradeLock: OK (player={}, villager={}, idx={}, mask={}, containerId={})",
                    sp.getGameProfile().getName(),
                    vill.getUUID(),
                    idx,
                    Long.toUnsignedString(next),
                    containerId
            );

            safeReply(ctx, new PacketTradeLocks(containerId, next));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleToggleTradeLock failed", t);
        }
    }

    public static void handleSearchCatalogQuery(PacketSearchCatalogQuery msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] handleSearchCatalogQuery: could not resolve villager");
                ctx.reply(new PacketSearchCatalogData(msg.villagerEntityId(), List.of()));
                return;
            }

            List<net.minecraft.world.item.ItemStack> items = CatalogBuilder.buildCatalog(vill);
            ctx.reply(new PacketSearchCatalogData(vill.getId(), items));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleSearchCatalogQuery failed", t);
        }
    }

    public static void handleStartAutoSearch(PacketStartAutoSearch msg, IPayloadContext ctx) {
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            Villager vill = resolveVillagerFor(sp, msg.villagerEntityId());
            if (vill == null) {
                EZVillagerReroll.LOG().warn("[EZVR] handleStartAutoSearch: could not resolve villager");
                return;
            }

            SearchService.start(sp, vill, msg.targets());

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
        try {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // No state change required; reroll continues. Log for observability.
            EZVillagerReroll.LOG().debug("[EZVR] ContinueAutoSearch received (player={} villagerEntityId={})",
                    sp.getGameProfile().getName(), msg.villagerEntityId());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleContinueAutoSearch failed", t);
        }
    }

    private static Villager resolveVillagerFor(ServerPlayer sp, int villagerEntityId) {
        try {
            if (villagerEntityId >= 0) {
                ServerLevel lvl = sp.serverLevel();
                Entity e = lvl.getEntity(villagerEntityId);
                if (e instanceof Villager v) return v;
            }

            if (sp.containerMenu instanceof MerchantMenu menu) {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Villager v) return v;
            }

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sendCurrentTradeLocksSnapshot(ServerPlayer sp, IPayloadContext ctx) {
        try {
            if (!(sp.containerMenu instanceof MerchantMenu menu)) return;

            int containerId = menu.containerId;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) {
                safeReply(ctx, new PacketTradeLocks(containerId, 0L));
                return;
            }

            long mask = TradeLockState.getMask(vill);
            int offerSize = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, offerSize);
            if (sanitized != mask) {
                TradeLockState.setMask(vill, sanitized);
                mask = sanitized;
            }

            safeReply(ctx, new PacketTradeLocks(containerId, mask));
        } catch (Throwable ignored) {}
    }

    private static void safeReply(IPayloadContext ctx, PacketTradeLocks msg) {
        try {
            ctx.reply(msg);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] safeReply(PacketTradeLocks) failed (soft): {}", t.toString());
        }
    }
}
