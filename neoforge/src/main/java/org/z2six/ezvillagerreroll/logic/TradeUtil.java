// MainFile: src/main/java/org/z2six/ezvillagerreroll/logic/TradeUtil.java
package org.z2six.ezvillagerreroll.logic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.mixin.AbstractVillagerAccessor;
import org.z2six.ezvillagerreroll.mixin.VillagerAccessor;

public final class TradeUtil {

    public static boolean rebuildOffers(Villager vill, ServerPlayer player) {
        try {
            final VillagerData original = vill.getVillagerData();
            final int targetLevel = Math.max(1, Math.min(5, original.getLevel()));
            final int oldSize = vill.getOffers() != null ? vill.getOffers().size() : -1;

            ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(new MerchantOffers());

            for (int l = 1; l <= targetLevel; l++) {
                VillagerData step = new VillagerData(original.getType(), original.getProfession(), l);
                vill.setVillagerData(step);
                ((VillagerAccessor) vill).ezvr$updateTrades(); // appends that level's offers
            }

            vill.setVillagerData(original);
            ((VillagerAccessor) vill).ezvr$updateSpecialPrices(player);

            MerchantOffers offers = vill.getOffers();
            int newSize = offers != null ? offers.size() : -1;

            if (player.containerMenu instanceof MerchantMenu menu) {
                player.sendMerchantOffers(
                        menu.containerId,
                        offers,
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );
                EZVillagerReroll.LOG().info(
                        "[EZVR] Rebuilt offers via vanilla steps: villager={}, level={}, offers {} -> {}",
                        vill.getUUID(), targetLevel, oldSize, newSize
                );
            } else {
                EZVillagerReroll.LOG().warn(
                        "[EZVR] Player not in MerchantMenu during sync; GUI may not refresh (villager={}, offers={})",
                        vill.getUUID(), newSize
                );
            }

            return true;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] rebuildOffers exception", t);
            return false;
        }
    }

    private TradeUtil() {}
}
