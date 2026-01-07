// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/config/ServerConfig.java
package org.z2six.ezvillagerreroll.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.List;
import java.util.Objects;

public final class ServerConfig {

    // ---------------------------------------------------------------------
    // Spec builder
    // ---------------------------------------------------------------------

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ---------------------------------------------------------------------
    // COST
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.ConfigValue<String> COST_ITEM_OR_TAG;
    public static final ModConfigSpec.BooleanValue PREFER_WALLET;
    public static final ModConfigSpec.BooleanValue AUTO_DEFAULT_LC_IF_PRESENT;

    // NOTE: Number, NOT Long (NeoForge may give Integer)
    public static final ModConfigSpec.ConfigValue<List<? extends Number>> LEVEL_COSTS;

    // ---------------------------------------------------------------------
    // LIMITS
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.IntValue COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue PER_VILLAGER_DAILY;

    // ---------------------------------------------------------------------
    // BEHAVIOR
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue ALLOW_AFTER_TRADE_USED;

    // ---------------------------------------------------------------------
    // SPEC DEFINITION (push/pop – REQUIRED)
    // ---------------------------------------------------------------------

    static {
        B.push("cost");

        COST_ITEM_OR_TAG =
                B.comment("""
                        Item or tag to consume per reroll:
                          - "minecraft:emerald"
                          - "lightmanscurrency:coin_emerald"
                          - "#minecraft:logs"
                        """)
                        .define("itemOrTag", "minecraft:emerald");

        PREFER_WALLET =
                B.comment("Prefer Lightman's Currency wallet when available.")
                        .define("preferWallet", true);

        AUTO_DEFAULT_LC_IF_PRESENT =
                B.comment("Auto-switch emerald to LC coin if LC is installed.")
                        .define("autoPreferLCIfPresent", true);

        LEVEL_COSTS =
                B.comment("""
                        Cost per villager level (1–5).
                        Example: [0, 16, 52, 64, 96]
                        """)
                        .defineListAllowEmpty(
                                "levelCosts",
                                () -> List.of(0, 16, 52, 64, 96),
                                o -> o instanceof Number n
                                        && n.longValue() >= 0
                                        && n.longValue() <= 640
                        );

        B.pop();

        B.push("limits");

        COOLDOWN_TICKS =
                B.comment("Cooldown in ticks per villager (0 = disabled)")
                        .defineInRange("cooldownTicks", 200, 0, 20_000);

        PER_VILLAGER_DAILY =
                B.comment("Max rerolls per villager per day (0 = disabled)")
                        .defineInRange("perVillagerDaily", 0, 0, 100);

        B.pop();

        B.push("behavior");

        ALLOW_AFTER_TRADE_USED =
                B.comment("Allow reroll after villager has XP / trades used.")
                        .define("allowAfterTradeUsed", true);

        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    // ---------------------------------------------------------------------
    // Runtime values
    // ---------------------------------------------------------------------

    public static String costSpec = "minecraft:emerald";
    public static boolean preferWallet = true;
    public static boolean autoPreferLCIfPresent = true;

    public static int cooldownTicks = 20;
    public static int perVillagerDaily = 0;
    public static boolean allowAfterTradeUsed = true;

    private static int[] levelCosts = new int[]{0, 16, 52, 64, 96};

    private static volatile int cfgVersion = 1;
    private static volatile int cfgHash = 0;

    private ServerConfig() {}

    // ---------------------------------------------------------------------
    // Load / reload hooks
    // ---------------------------------------------------------------------

    public static void onConfigLoading(ModConfigEvent.Loading e) {
        if (e.getConfig().getSpec() == SPEC) reload("loading");
    }

    public static void onConfigReloading(ModConfigEvent.Reloading e) {
        if (e.getConfig().getSpec() == SPEC) reload("reloading");
    }

    private static void reload(String reason) {
        try {
            costSpec = COST_ITEM_OR_TAG.get();
            preferWallet = PREFER_WALLET.get();
            autoPreferLCIfPresent = AUTO_DEFAULT_LC_IF_PRESENT.get();

            cooldownTicks = COOLDOWN_TICKS.get();
            perVillagerDaily = PER_VILLAGER_DAILY.get();
            allowAfterTradeUsed = ALLOW_AFTER_TRADE_USED.get();

            levelCosts = parseLevelCosts(LEVEL_COSTS.get());

            if (autoPreferLCIfPresent
                    && ModList.get().isLoaded("lightmanscurrency")
                    && "minecraft:emerald".equals(costSpec)) {
                costSpec = "lightmanscurrency:coin_emerald";
                EZVillagerReroll.LOG().info("[EZVR] Auto-switched cost to LC emerald coin");
            }

            cfgVersion++;
            cfgHash = computeHash();

            EZVillagerReroll.LOG().info(
                    "[EZVR] ServerConfig {} OK | v={} hash={} costs={}",
                    reason, cfgVersion, cfgHash, debug(levelCosts)
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig reload failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // REQUIRED API
    // ---------------------------------------------------------------------

    /** @return defensive copy, length = 5 */
    public static int[] costsByLevel5() {
        int[] out = new int[5];
        System.arraycopy(levelCosts, 0, out, 0, Math.min(levelCosts.length, 5));
        return out;
    }

    public static int costForVillagerLevel(int level) {
        int l = Math.max(1, Math.min(5, level));
        return levelCosts[l - 1];
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static int[] parseLevelCosts(List<? extends Number> raw) {
        int[] out = new int[]{0, 16, 52, 64, 96};
        if (raw == null) return out;

        for (int i = 0; i < 5 && i < raw.size(); i++) {
            long v = raw.get(i).longValue();
            if (v < 0) v = 0;
            if (v > Integer.MAX_VALUE) v = Integer.MAX_VALUE;
            out[i] = (int) v;
        }
        return out;
    }

    private static int computeHash() {
        int h = 1;
        h = 31 * h + Objects.hashCode(costSpec);
        h = 31 * h + (preferWallet ? 1 : 0);
        h = 31 * h + cooldownTicks;
        h = 31 * h + perVillagerDaily;
        h = 31 * h + (allowAfterTradeUsed ? 1 : 0);
        for (int v : levelCosts) h = 31 * h + v;
        return h;
    }

    private static String debug(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    public static boolean isTagSpec(String s) {
        return s != null && s.startsWith("#");
    }

    public static TagKey<Item> asItemTag(String s) {
        if (!isTagSpec(s)) return null;
        ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
        return id == null ? null : TagKey.create(BuiltInRegistries.ITEM.key(), id);
    }

    public static int cfgVersion() { return cfgVersion; }
    public static int cfgHash() { return cfgHash; }
}
