// neoforge\src\main\java\org\z2six\villageroverhaul\logic\CostUtil.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class CostUtil {

    public static Ingredient parseIngredient(String spec) {
        try {
            if (spec == null || spec.isBlank()) return Ingredient.EMPTY;
            spec = spec.trim();
            if (spec.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(spec.substring(1));
                if (id == null) return Ingredient.EMPTY;
                TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), id);
                return Ingredient.of(tag);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(spec);
                if (id == null) return Ingredient.EMPTY;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == null) return Ingredient.EMPTY;
                return Ingredient.of(item);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] parseIngredient failed for '{}'", spec, t);
            return Ingredient.EMPTY;
        }
    }

    public static boolean consume(net.minecraft.server.level.ServerPlayer sp, Ingredient ing, int count) {
        try {
            if (count <= 0) return true;
            Inventory inv = sp.getInventory();
            int remaining = count;

            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty()) continue;
                if (!ing.test(s)) continue;

                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;

                if (remaining <= 0) break;
            }
            if (remaining > 0) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Not enough items: need {}, short by {}", count, remaining);
                return false;
            }
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] consume() exception", t);
            return false;
        }
    }

    private CostUtil() {}
}
