// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/BusyVillagerBlocker.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * Prevent opening the vanilla Merchant UI while a villager is "busy" searching,
 * and instead open our BusyVillagerScreen.
 *
 * NeoForge 1.21.1 note:
 * - Use gameplay bus (NeoForge.EVENT_BUS).
 * - Use PlayerInteractEvent.EntityInteract (right-click entity).
 */
public final class BusyVillagerBlocker {

    private BusyVillagerBlocker() {}

    public static void register(IEventBus bus) {
        try {
            bus.addListener(BusyVillagerBlocker::onEntityInteract);
            EZVillagerReroll.LOG().info("[EZVR] BusyVillagerBlocker registered.");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker.register failed", t);
        }
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        try {
            if (e == null) return;
            if (e.getLevel() == null || e.getLevel().isClientSide) return;

            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getTarget() instanceof Villager vill)) return;

            // Only care about main-hand RMB behavior; offhand can produce double-fires on some setups.
            InteractionHand hand = e.getHand();
            if (hand != InteractionHand.MAIN_HAND) {
                EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerBlocker: ignoring offhand interact (player={} villager={})",
                        sp.getGameProfile().getName(), vill.getUUID());
                return;
            }

            if (!SearchService.isBusy(vill)) return;

            // Busy: block vanilla open and open our busy screen.
            EZVillagerReroll.LOG().debug("[EZVR] BusyVillagerBlocker: intercepted interact -> open Busy screen (player={} villager={} entityId={})",
                    sp.getGameProfile().getName(), vill.getUUID(), vill.getId());

            // Cancel the interaction so vanilla doesn't open Merchant UI.
            e.setCanceled(true);
            try {
                e.setCancellationResult(InteractionResult.SUCCESS);
            } catch (Throwable ignored) {
                // Some mappings only support cancel; soft.
            }

            // Open our Busy screen packet to that player.
            try {
                SearchService.openBusyScreen(sp, vill);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker: failed opening Busy screen (soft)", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker.onEntityInteract failed", t);
        }
    }
}
