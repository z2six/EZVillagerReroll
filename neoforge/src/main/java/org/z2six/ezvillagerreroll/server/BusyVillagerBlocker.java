// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/BusyVillagerBlocker.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * Server-authoritative block: if a villager is busy searching, deny interaction
 * and open the Busy screen instead of the trading menu.
 */
public final class BusyVillagerBlocker {

    private BusyVillagerBlocker() {}

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        try {
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            if (!(e.getTarget() instanceof Villager vill)) return;

            if (!SearchService.isBusy(vill)) return;

            // Deny vanilla open-trade flow
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);

            // Open our busy screen instead
            SearchService.openBusyScreen(sp, vill);

            EZVillagerReroll.LOG().debug("[EZVR] Blocked trade open: villager={} player={}", vill.getUUID(), sp.getGameProfile().getName());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] BusyVillagerBlocker.onEntityInteract failed", t);
        }
    }
}
