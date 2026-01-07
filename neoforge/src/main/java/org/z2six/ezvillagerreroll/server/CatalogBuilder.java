// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/CatalogBuilder.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.*;

/**
 * Builds a catalog of trade outputs for a villager.
 *
 * NOTE (important):
 * True "complete & accurate" across all possible rerolls is non-trivial.
 * This implementation returns the villager's CURRENT trade outputs as a baseline.
 * You can later extend it to sample multiple rerolls to approximate the full domain.
 */
public final class CatalogBuilder {

    private CatalogBuilder() {}

    public static List<ItemStack> buildCatalog(Villager vill) {
        try {
            if (vill == null) return List.of();

            MerchantOffers offers = vill.getOffers();
            if (offers == null || offers.isEmpty()) return List.of();

            Map<String, ItemStack> unique = new LinkedHashMap<>();
            for (MerchantOffer o : offers) {
                if (o == null) continue;
                ItemStack out = o.getResult();
                if (out == null || out.isEmpty()) continue;

                String k = keyOf(out);
                unique.putIfAbsent(k, out.copy());
            }

            List<ItemStack> list = new ArrayList<>(unique.values());
            EZVillagerReroll.LOG().debug("[EZVR] buildCatalog: villager={} outputs={}", vill.getUUID(), list.size());
            return list;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] CatalogBuilder.buildCatalog failed", t);
            return List.of();
        }
    }

    /**
     * Key used for de-duplication. Uses component patch when present (1.21+).
     */
    public static String keyOf(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";

            String itemPart = String.valueOf(s.getItem());

            String compPart;
            try {
                Object patch = s.getComponentsPatch();
                compPart = (patch == null) ? "noComponents" : patch.toString();
            } catch (Throwable ignored) {
                compPart = s.toString();
            }

            return itemPart + "|" + compPart;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.keyOf failed (soft): {}", t.toString());
            return "err|" + Objects.hashCode(s);
        }
    }
}
