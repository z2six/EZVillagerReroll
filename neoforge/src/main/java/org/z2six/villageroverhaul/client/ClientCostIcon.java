package org.z2six.villageroverhaul.client;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.logic.CostUtil;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;

/**
 * Client-side helper for rendering the server-configured currency icon.
 */
public final class ClientCostIcon {

    private ClientCostIcon() {}

    public static ItemStack costIcon() {
        try {
            String spec = "minecraft:emerald";
            var cfg = ClientSyncedConfig.get();
            if (cfg != null && cfg.costItemOrTag != null && !cfg.costItemOrTag.isBlank()) spec = cfg.costItemOrTag;
            return costIcon(spec);
        } catch (Throwable ignored) {
            return new ItemStack(Items.EMERALD);
        }
    }

    public static ItemStack costIcon(String spec) {
        try {
            if (spec == null || spec.isBlank()) return new ItemStack(Items.EMERALD);
            Ingredient ing = CostUtil.parseIngredient(spec);
            if (ing == Ingredient.EMPTY) return new ItemStack(Items.EMERALD);

            ItemStack[] items = ing.getItems();
            if (items == null || items.length <= 0 || items[0] == null || items[0].isEmpty()) return new ItemStack(Items.EMERALD);

            ItemStack icon = items[0].copy();
            icon.setCount(1);
            return icon;
        } catch (Throwable ignored) {
            return new ItemStack(Items.EMERALD);
        }
    }

    /**
     * Builds a human-readable "(cost)" component like "128 x Emerald Coin".
     * For tag-based currency, this uses the icon item (first matching item in the tag).
     */
    public static Component costText(String amount) {
        try {
            if (amount == null) amount = "0";
            ItemStack icon = costIcon();
            Component name = icon.getHoverName();
            return Component.empty()
                    .append(Component.literal(amount).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" x ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(name.copy().withStyle(ChatFormatting.DARK_GRAY));
        } catch (Throwable ignored) {
            return Component.literal(String.valueOf(amount));
        }
    }

    public static Component costText(long amount) {
        return costText(String.valueOf(Math.max(0L, amount)));
    }
}
