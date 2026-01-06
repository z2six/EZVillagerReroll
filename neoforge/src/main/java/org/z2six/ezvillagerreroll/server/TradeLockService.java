// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/TradeLockService.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.TradeLockState;
import org.z2six.ezvillagerreroll.network.PacketTradeLocks;

public final class TradeLockService {

    public static PacketTradeLocks computeSnapshot(ServerPlayer player, int traderEntityId) {
        PacketTradeLocks out = new PacketTradeLocks(traderEntityId, 0L);
        try {
            if (player == null) {
                EZVillagerReroll.LOG().warn("[EZVR] TradeLockService snapshot: player is null; returning empty");
                return out;
            }

            Villager vill = null;

            if (traderEntityId >= 0) {
                Entity e = player.level().getEntity(traderEntityId);
                if (e instanceof Villager v) vill = v;
            }

            if (vill == null) {
                EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: no villager for traderEntityId={}", traderEntityId);
                return out;
            }

            long mask = TradeLockState.getMask(vill);

            // sanitize against current offer size
            int size = vill.getOffers() == null ? 0 : vill.getOffers().size();
            long sanitized = TradeLockState.sanitizeMaskForSize(mask, size);
            if (sanitized != mask) {
                TradeLockState.setMask(vill, sanitized);
                EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: sanitized mask (villager={} size={} before={} after={})",
                        vill.getUUID(), size, Long.toUnsignedString(mask), Long.toUnsignedString(sanitized));
                mask = sanitized;
            }

            out.traderEntityId = traderEntityId;
            out.mask = mask;

            EZVillagerReroll.LOG().debug("[EZVR] TradeLockService snapshot: traderEntityId={} mask={}",
                    traderEntityId, Long.toUnsignedString(mask));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] TradeLockService.computeSnapshot failed", t);
        }
        return out;
    }

    private TradeLockService() {}
}
