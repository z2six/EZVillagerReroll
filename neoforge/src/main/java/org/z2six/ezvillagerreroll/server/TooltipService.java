// MainFile: src/main/java/org/z2six/ezvillagerreroll/server/TooltipService.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.logic.MoneyBridge;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;

public final class TooltipService {

    public static PacketTooltipData computeSnapshot(ServerPlayer player, int traderEntityId) {
        var out = new PacketTooltipData();
        try {
            Villager vill = null;
            if (traderEntityId >= 0) {
                Entity e = player.level().getEntity(traderEntityId);
                if (e instanceof Villager v) vill = v;
            }

            int villLevel = vill != null ? vill.getVillagerData().getLevel() : 1;
            int villXp = vill != null ? vill.getVillagerXp() : 0;

            String costSpec = ServerConfig.costSpec == null ? "minecraft:emerald" : ServerConfig.costSpec;
            boolean preferWallet = ServerConfig.preferWallet;
            int cost = ServerConfig.costForVillagerLevel(Math.max(1, Math.min(5, villLevel)));

            // Tooltip fields
            out.cost.itemOrTag = costSpec;
            out.cost.item = (!ServerConfig.isTagSpec(costSpec)) ? ResourceLocation.tryParse(costSpec) : null;
            out.cost.baseCost = cost;
            out.cost.scaledCost = cost;

            // Next cost is the next level cost (if different)
            Integer next = null;
            int nextLevel = Math.max(1, Math.min(5, villLevel + 1));
            int nextCost = ServerConfig.costForVillagerLevel(nextLevel);
            if (nextLevel != villLevel && nextCost != cost) next = nextCost;
            out.cost.nextCostIfUsed = next;

            // Max is max across level costs
            int max = 0;
            for (int i = 0; i <= 5; i++) max = Math.max(max, ServerConfig.costForVillagerLevel(i));
            out.cost.maxCostPossible = (max > 0 ? max : null);

            out.villager.level = villLevel;
            out.villager.xp = villXp;

            // Afford check: wallet only for exact items
            if (cost <= 0) {
                out.afford.canAfford = true;
                out.afford.source = "none";
            } else {
                boolean walletOK = false;
                boolean invOK = false;

                if (preferWallet && out.cost.item != null && MoneyBridge.isLCPresent()) {
                    walletOK = MoneyBridge.canAfford(player, out.cost.item, cost);
                }

                if (out.cost.item != null) {
                    Item item = BuiltInRegistries.ITEM.get(out.cost.item);
                    if (item != null) {
                        int invCount = countInInventory(player, item);
                        invOK = invCount >= cost;
                    }
                } else {
                    // Tag affordability is not computed precisely here (could be expensive);
                    // Show inventory-only unknown as false unless player obviously has enough by scanning ingredient.
                    // Keep it simple: treat as inventory-only and "unknown" -> false.
                    invOK = false;
                }

                boolean can = walletOK || invOK;
                out.afford.canAfford = can;
                out.afford.source = can ? (walletOK && invOK ? "both" : (walletOK ? "wallet" : "inventory")) : "none";
            }

            boolean capEnabled = ServerConfig.perVillagerDaily > 0;
            out.cap.enabled = capEnabled;
            out.cap.cap = ServerConfig.perVillagerDaily;
            out.cap.remaining = -1; // Keeping the old tooltip shape without adding per-player tracking here; server enforces anyway.

            out.cfg.version = ServerConfig.cfgVersion();
            out.cfg.hash = ServerConfig.cfgHash();
            out.cfg.preferWallet = preferWallet;
            out.cfg.freeMode = (cost <= 0);
            out.cfg.capEnabled = capEnabled;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] TooltipService.computeSnapshot failed", t);
        }
        return out;
    }

    private static int countInInventory(ServerPlayer player, Item item) {
        int total = 0;
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
            }
        } catch (Throwable ignored) {}
        return total;
    }

    private TooltipService() {}
}
