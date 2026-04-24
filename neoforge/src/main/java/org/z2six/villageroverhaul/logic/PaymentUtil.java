package org.z2six.villageroverhaul.logic;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
            if (isExactEmerald(id, isTag)) {
                return tryChargeEmeraldExact(sp, cost);
            }

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

    private static boolean isExactEmerald(ResourceLocation id, boolean isTag) {
        return !isTag
                && id != null
                && "minecraft".equals(id.getNamespace())
                && "emerald".equals(id.getPath());
    }

    private static boolean tryChargeEmeraldExact(net.minecraft.server.level.ServerPlayer sp, int cost) {
        int remaining = Math.max(0, cost);
        int withdrawnFromPouch = 0;

        try {
            long stored = EmeraldPouchBridge.getStoredEmeralds(sp);
            if (stored > 0L) {
                withdrawnFromPouch = (int) Math.min((long) remaining, stored);
                long taken = EmeraldPouchBridge.withdraw(sp, withdrawnFromPouch);
                if (taken != withdrawnFromPouch) {
                    if (taken > 0L) EmeraldPouchBridge.give(sp, taken);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] PaymentUtil: pouch withdraw mismatch wanted={} got={}", withdrawnFromPouch, taken);
                    return false;
                }
                remaining -= withdrawnFromPouch;
            }

            if (remaining <= 0) return true;
            if (CostUtil.consume(sp, Ingredient.of(Items.EMERALD), remaining)) return true;

            if (withdrawnFromPouch > 0) {
                EmeraldPouchBridge.give(sp, withdrawnFromPouch);
            }
            return false;
        } catch (Throwable t) {
            if (withdrawnFromPouch > 0) {
                try { EmeraldPouchBridge.give(sp, withdrawnFromPouch); } catch (Throwable ignored) {}
            }
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] PaymentUtil.tryChargeEmeraldExact failed (soft): {}", t.toString());
            return false;
        }
    }
}
