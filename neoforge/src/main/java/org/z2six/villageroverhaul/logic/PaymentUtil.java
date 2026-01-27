package org.z2six.villageroverhaul.logic;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

/**
 * Shared server-side currency payment helper.
 * Uses the server-configured cost spec (exact item id or #tag), with optional Lightman's Currency wallet support.
 */
public final class PaymentUtil {

    private PaymentUtil() {}

    public static String costSpec() {
        String s = ServerConfig.costSpec;
        if (s == null || s.isBlank()) return "minecraft:emerald";
        return s.trim();
    }

    public static Ingredient costIngredient() {
        return CostUtil.parseIngredient(costSpec());
    }

    public static boolean matchesCost(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            Ingredient ing = costIngredient();
            return ing != Ingredient.EMPTY && ing.test(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean tryCharge(net.minecraft.server.level.ServerPlayer sp, int cost) {
        try {
            if (sp == null) return false;
            if (cost <= 0) return true;

            String spec = costSpec();
            boolean isTag = ServerConfig.isTagSpec(spec);
            ResourceLocation id = isTag ? null : ResourceLocation.tryParse(spec);

            if (ServerConfig.preferWallet && id != null && MoneyBridge.isLCPresent()) {
                if (MoneyBridge.tryExtract(sp, id, cost)) return true;
            }

            if (ServerConfig.preferWallet && id != null && WalletBridge.isLCPresent()) {
                if (WalletBridge.tryWithdrawFromWallet(sp, id, cost)) return true;
            }

            Ingredient ing = CostUtil.parseIngredient(spec);
            return ing != Ingredient.EMPTY && CostUtil.consume(sp, ing, cost);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] PaymentUtil.tryCharge failed (soft): {}", t.toString());
            return false;
        }
    }
}
