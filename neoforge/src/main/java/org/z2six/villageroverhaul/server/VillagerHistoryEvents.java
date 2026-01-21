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

    private record TradeSession(int villagerEntityId, UUID villagerUuid, int usesSum) {}

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

            int sum = sumOfferUses(vill);
            TRADE_SESSIONS.put(sp.getUUID(), new TradeSession(vill.getId(), vill.getUUID(), sum));
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

            int sumNow = sumOfferUses(vill);
            int delta = Math.max(0, sumNow - session.usesSum);
            if (delta > 0) {
                VillagerHistoryService.addTradesCompleted(vill, delta);
            }
        } catch (Throwable ignored) {}
    }

    private static int sumOfferUses(Villager vill) {
        try {
            if (vill == null) return 0;
            var offers = vill.getOffers();
            if (offers == null) return 0;
            int sum = 0;
            for (MerchantOffer o : offers) {
                if (o == null) continue;
                sum += Math.max(0, o.getUses());
            }
            return sum;
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
                    VillagerHistoryService.addDeath(dv, 1);
                    RespawnService.captureOnDeath(dv);
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
