package org.z2six.villageroverhaul.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.logic.HoarderOffers;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks open Merchant menus and re-normalizes Hoarder offers if vanilla/mods change the offer list while the UI is open
 * (e.g., delayed level-up flow, modded offer injections, etc.).
 */
public final class HoarderOfferService {

    private HoarderOfferService() {}

    private static final class Session {
        final UUID playerUuid;
        final UUID villagerUuid;
        final int containerId;
        int lastOfferSize;
        long lastEnforceGameTime;

        Session(UUID playerUuid, UUID villagerUuid, int containerId, int lastOfferSize, long now) {
            this.playerUuid = playerUuid;
            this.villagerUuid = villagerUuid;
            this.containerId = containerId;
            this.lastOfferSize = lastOfferSize;
            this.lastEnforceGameTime = now;
        }
    }

    private static final Map<UUID, Session> SESSIONS_BY_PLAYER = new ConcurrentHashMap<>();

    public static void onMerchantMenuOpen(ServerPlayer sp, MerchantMenu menu, Villager vill) {
        try {
            if (sp == null || menu == null || vill == null) return;

            // Don’t mess with settlement states.
            if (SearchService.isAwaitingPayment(vill)) return;

            // Enforce immediately so UI opens with correct count.
            HoarderOffers.normalizeOffers(vill, sp);

            int size = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
            long now = sp.serverLevel().getGameTime();

            SESSIONS_BY_PLAYER.put(sp.getUUID(), new Session(sp.getUUID(), vill.getUUID(), menu.containerId, size, now));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HoarderOfferService.onMerchantMenuOpen failed (soft): {}", t.toString());
        }
    }

    public static void onMerchantMenuClose(ServerPlayer sp) {
        try {
            if (sp == null) return;
            SESSIONS_BY_PLAYER.remove(sp.getUUID());
        } catch (Throwable ignored) {}
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (SESSIONS_BY_PLAYER.isEmpty()) return;

            long gt = server.overworld().getGameTime();

            // Don’t do it every tick; still catches changes quickly.
            boolean periodicForce = (gt % 100L) == 0L;
            if (!periodicForce && (gt % 5L) != 0L) return;

            Iterator<Map.Entry<UUID, Session>> it = SESSIONS_BY_PLAYER.entrySet().iterator();
            while (it.hasNext()) {
                Session s = it.next().getValue();
                if (s == null) { it.remove(); continue; }

                ServerPlayer sp = server.getPlayerList().getPlayer(s.playerUuid);
                if (sp == null) { it.remove(); continue; }

                if (!(sp.containerMenu instanceof MerchantMenu menu)) { it.remove(); continue; }
                if (menu.containerId != s.containerId) { it.remove(); continue; }

                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (!(trader instanceof Villager vill)) { it.remove(); continue; }
                if (!vill.getUUID().equals(s.villagerUuid)) { it.remove(); continue; }

                // Don’t mess with settlement states.
                if (SearchService.isAwaitingPayment(vill)) { it.remove(); continue; }

                int sizeNow = (vill.getOffers() == null) ? 0 : vill.getOffers().size();
                boolean needs = periodicForce || sizeNow != s.lastOfferSize || (gt - s.lastEnforceGameTime) >= 100L;

                if (!needs) continue;

                boolean changed = HoarderOffers.normalizeOffers(vill, sp);
                int sizeAfter = (vill.getOffers() == null) ? 0 : vill.getOffers().size();

                s.lastOfferSize = sizeAfter;
                s.lastEnforceGameTime = gt;

                if (changed && VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] HoarderOfferService: re-normalized offers while trading (player={} villager={} {}->{}).",
                            sp.getGameProfile().getName(), vill.getUUID(), sizeNow, sizeAfter);
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HoarderOfferService.tick failed (soft): {}", t.toString());
        }
    }
}
