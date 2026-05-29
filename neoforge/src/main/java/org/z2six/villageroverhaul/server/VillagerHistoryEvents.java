// neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerHistoryEvents.java
package org.z2six.villageroverhaul.server;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VillagerHistoryEvents {
    private VillagerHistoryEvents() {}

    private record TradeSession(int villagerEntityId, UUID villagerUuid, int[] usesByOffer) {}

    private static final Map<UUID, TradeSession> TRADE_SESSIONS = new HashMap<>();

    public static void register(IEventBus bus) {
        if (bus == null) return;
        bus.addListener(VillagerHistoryEvents::onContainerOpen);
        bus.addListener(VillagerHistoryEvents::onContainerClose);
        bus.addListener(VillagerHistoryEvents::onLivingDeath);
        bus.addListener(EventPriority.LOWEST, VillagerHistoryEvents::onIncomingDamageLowest);
    }

    private static void onContainerOpen(PlayerContainerEvent.Open e) {
        try {
            if (e == null) return;
            if (!(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;
            if (!RecruitService.isRecruited(vill)) return;

            VillagerHistoryService.addMerchantMenuOpen(vill, 1);

            int[] uses = snapshotOfferUses(vill);
            TRADE_SESSIONS.put(sp.getUUID(), new TradeSession(vill.getId(), vill.getUUID(), uses));
        } catch (Throwable ignored) {}
    }

    private static void onContainerClose(PlayerContainerEvent.Close e) {
        try {
            if (e == null) return;
            if (!(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(e.getContainer() instanceof MerchantMenu menu)) return;

            TradeSession session = TRADE_SESSIONS.remove(sp.getUUID());
            if (session == null) return;

            var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (!(trader instanceof Villager vill)) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (!vill.getUUID().equals(session.villagerUuid)) return;

            int deltaTrades = 0;
            long emeralds = 0L;
            try {
                var offers = vill.getOffers();
                int[] before = session.usesByOffer == null ? null : session.usesByOffer;
                int n = offers == null ? 0 : offers.size();
                for (int i = 0; i < n; i++) {
                    MerchantOffer o = offers.get(i);
                    if (o == null) continue;
                    int beforeUses = (before != null && i < before.length) ? Math.max(0, before[i]) : 0;
                    int nowUses = Math.max(0, o.getUses());
                    int d = Math.max(0, nowUses - beforeUses);
                    if (d <= 0) continue;
                    deltaTrades += d;
                    emeralds += (long) d * (long) emeraldCost(o);
                }
            } catch (Throwable ignored) {}

            if (deltaTrades > 0) VillagerHistoryService.addTradesCompleted(vill, deltaTrades);
            if (emeralds > 0L) VillagerHistoryService.addEmeraldsFromTrades(vill, emeralds);
        } catch (Throwable ignored) {}
    }

    private static int[] snapshotOfferUses(Villager vill) {
        try {
            if (vill == null) return new int[0];
            var offers = vill.getOffers();
            if (offers == null) return new int[0];
            int n = Math.min(64, offers.size());
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                MerchantOffer o = offers.get(i);
                out[i] = o == null ? 0 : Math.max(0, o.getUses());
            }
            return out;
        } catch (Throwable ignored) {
            return new int[0];
        }
    }

    private static int emeraldCost(MerchantOffer o) {
        try {
            if (o == null) return 0;
            int n = 0;
            n += emeraldCount(o.getCostA());
            n += emeraldCount(o.getCostB());
            return Math.max(0, n);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int emeraldCount(net.minecraft.world.item.ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return 0;
            if (st.is(net.minecraft.world.item.Items.EMERALD)) return Math.max(0, st.getCount());
            if (st.is(net.minecraft.world.item.Items.EMERALD_BLOCK)) return Math.max(0, st.getCount()) * 9;
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void onLivingDeath(LivingDeathEvent e) {
        try {
            if (e == null) return;
            LivingEntity dead = e.getEntity();
            if (dead == null) return;
            if (dead.level() == null || dead.level().isClientSide()) return;

            // If a recruited villager died, snapshot it for respawn.
            try {
                if (dead instanceof Villager dv && RecruitService.isRecruited(dv)) {
                    if (!VillagerReleaseService.isReleasedNoRespawn(dv)) {
                        VillagerHistoryService.addDeath(dv, 1);
                        RespawnService.captureOnDeath(dv);
                    }
                }
            } catch (Throwable ignored) {}

            var src = e.getSource();
            Entity killer = src == null ? null : src.getEntity();
            if (!(killer instanceof Villager vill)) return;
            if (!RecruitService.isRecruited(vill)) return;

            VillagerHistoryService.addKill(vill, 1);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHistoryEvents.onLivingDeath failed (soft): {}", t.toString());
        }
    }

    private static void onIncomingDamageLowest(LivingIncomingDamageEvent e) {
        try {
            if (e == null) return;
            if (e.isCanceled()) return;

            LivingEntity victim = e.getEntity();
            if (victim == null || victim.level() == null || victim.level().isClientSide()) return;

            var src = e.getSource();
            Entity attacker = src == null ? null : src.getEntity();
            if (!(attacker instanceof Villager vill)) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (victim == vill) return;

            float amount = e.getAmount();
            if (amount <= 0.0f) return;

            VillagerHistoryService.addDamageDealt(vill, amount);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHistoryEvents.onIncomingDamageLowest failed (soft): {}", t.toString());
        }
    }
}
