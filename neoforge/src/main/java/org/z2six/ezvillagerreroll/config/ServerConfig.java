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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ServerConfig {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> COST_ITEM_OR_TAG =
            B.comment("""
                    Item or tag to consume per reroll (exactly one string):
                      - vanilla emerald: "minecraft:emerald"
                      - Lightman's Currency coins (normal):
                        "lightmanscurrency:coin_copper"
                        "lightmanscurrency:coin_iron"
                        "lightmanscurrency:coin_gold"
                        "lightmanscurrency:coin_emerald"
                        "lightmanscurrency:coin_diamond"
                        "lightmanscurrency:coin_netherite"
                      - Lightman's Currency chocolate coins (if enabled), e.g.:
                        "lightmanscurrency:coin_chocolate_copper"
                        "lightmanscurrency:coin_chocolate_emerald"
                      - any tag: "#minecraft:logs" (applies to inventory payment only)
                    """)
                    .define("cost.itemOrTag", "minecraft:emerald");

    public static final ModConfigSpec.BooleanValue PREFER_WALLET =
            B.comment("""
                    If true and Lightman's Currency is installed, attempt to withdraw the cost
                    from the player's LC Wallet first (or MoneyAPI when available). If that fails,
                    fall back to inventory.
                    """).define("cost.preferWallet", true);

    public static final ModConfigSpec.BooleanValue AUTO_DEFAULT_LC_IF_PRESENT =
            B.comment("""
                    QoL: If LC is installed and cost.itemOrTag is still "minecraft:emerald",
                    auto-switch to "lightmanscurrency:coin_emerald" on load (logged).
                    """).define("cost.autoPreferLCIfPresent", true);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> LEVEL_COSTS =
            B.comment("""
                    Costs by villager level.
                    Index 0..4 correspond to villager levels 1..5.
                    Example: [0,16,52,64,96] means:
                      level 1 = 0
                      level 2 = 16
                      level 3 = 52
                      level 4 = 64
                      level 5 = 96
                    """).defineListAllowEmpty(
                    List.of("cost.levelCosts"),
                    () -> List.of(0, 16, 52, 64, 96),
                    o -> o instanceof Integer i && i >= 0 && i <= 640
            );

    public static final ModConfigSpec.IntValue COOLDOWN_TICKS =
            B.comment("Cooldown in ticks per villager between rerolls (0 = disabled)")
                    .defineInRange("limits.cooldownTicks", 200, 0, 20_000);

    public static final ModConfigSpec.IntValue PER_VILLAGER_DAILY =
            B.comment("Maximum rerolls per villager per Minecraft day (0 = disabled)")
                    .defineInRange("limits.perVillagerDaily", 0, 0, 100);

    public static final ModConfigSpec.BooleanValue ALLOW_AFTER_TRADE_USED =
            B.comment("Allow reroll even if villager has XP / trades were used. Default true.")
                    .define("behavior.allowAfterTradeUsed", true);

    public static final ModConfigSpec SPEC = B.build();

    // ---- Runtime mirrors (server-authoritative) ----
    public static String costSpec = "minecraft:emerald";
    public static boolean preferWallet = true;
    public static boolean autoPreferLCIfPresent = true;

    public static int cooldownTicks = 200;
    public static int perVillagerDaily = 0;
    public static boolean allowAfterTradeUsed = true;

    // length 5 for villager levels 1..5
    private static int[] levelCosts = new int[]{0, 16, 52, 64, 96};

    // Used by tooltip + client sync
    private static volatile int cfgVersion = 1;
    private static volatile int cfgHash = 0;

    private ServerConfig() {}

    public static void onConfigLoading(final ModConfigEvent.Loading e) {
        try {
            if (e == null || e.getConfig() == null || e.getConfig().getSpec() != SPEC) return;
            reloadFromSpec("loading");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig onConfigLoading failed.", t);
        }
    }

    public static void onConfigReloading(final ModConfigEvent.Reloading e) {
        try {
            if (e == null || e.getConfig() == null || e.getConfig().getSpec() != SPEC) return;
            reloadFromSpec("reloading");
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig onConfigReloading failed.", t);
        }
    }

    private static void reloadFromSpec(String reason) {
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
                EZVillagerReroll.LOG().info("[EZVR] Lightman's Currency detected. Auto-switching cost.itemOrTag to '{}'", costSpec);
            }

            cfgVersion++;
            cfgHash = computeHash();

            EZVillagerReroll.LOG().info(
                    "[EZVR] ServerConfig {}: version={}, hash={}, costSpec='{}', preferWallet={}, autoPreferLCIfPresent={}, cooldownTicks={}, perVillagerDaily={}, allowAfterTradeUsed={}, levelCosts={}",
                    reason, cfgVersion, cfgHash,
                    costSpec, preferWallet, autoPreferLCIfPresent, cooldownTicks, perVillagerDaily, allowAfterTradeUsed,
                    toDebugString(levelCosts)
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig reloadFromSpec failed.", t);
        }
    }

    private static int computeHash() {
        try {
            int h = 1;
            h = 31 * h + Objects.hashCode(costSpec);
            h = 31 * h + (preferWallet ? 1 : 0);
            h = 31 * h + (autoPreferLCIfPresent ? 1 : 0);
            h = 31 * h + cooldownTicks;
            h = 31 * h + perVillagerDaily;
            h = 31 * h + (allowAfterTradeUsed ? 1 : 0);
            if (levelCosts != null) {
                for (int v : levelCosts) h = 31 * h + v;
            }
            return h;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int[] parseLevelCosts(List<? extends Integer> raw) {
        int[] out = new int[]{0, 16, 52, 64, 96};
        try {
            if (raw == null || raw.isEmpty()) return out;

            List<Integer> cleaned = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Integer i) cleaned.add(Math.max(0, i));
            }

            for (int i = 0; i < 5; i++) {
                if (i < cleaned.size()) out[i] = Math.max(0, cleaned.get(i));
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] parseLevelCosts failed; using defaults. {}", t.toString());
        }
        return out;
    }

    private static String toDebugString(int[] arr) {
        try {
            if (arr == null) return "null";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i != 0) sb.append(",");
                sb.append(arr[i]);
            }
            sb.append("]");
            return sb.toString();
        } catch (Throwable ignored) {
            return "[?]";
        }
    }

    public static int costForVillagerLevel(int villagerLevel) {
        try {
            int lvl = Math.max(1, Math.min(5, villagerLevel));
            int idx = lvl - 1;
            if (levelCosts == null || levelCosts.length < 5) return 0;
            return Math.max(0, levelCosts[idx]);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] costForVillagerLevel failed: {}", t.toString());
            return 0;
        }
    }

    public static boolean isTagSpec(String s) {
        return s != null && s.startsWith("#");
    }

    public static TagKey<Item> asItemTag(String s) {
        try {
            if (!isTagSpec(s)) return null;
            ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
            if (id == null) return null;
            return TagKey.create(BuiltInRegistries.ITEM.key(), id);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().warn("[EZVR] asItemTag failed for '{}': {}", s, t.toString());
            return null;
        }
    }

    public static ResourceLocation costItemRL() {
        try {
            if (costSpec == null) return ResourceLocation.withDefaultNamespace("emerald");
            if (isTagSpec(costSpec)) return ResourceLocation.withDefaultNamespace("emerald");
            ResourceLocation rl = ResourceLocation.tryParse(costSpec);
            return rl == null ? ResourceLocation.withDefaultNamespace("emerald") : rl;
        } catch (Throwable t) {
            return ResourceLocation.withDefaultNamespace("emerald");
        }
    }

    public static int cfgVersion() {
        return cfgVersion;
    }

    public static int cfgHash() {
        return cfgHash;
    }

    public static int[] costsByLevel5() {
        return levelCosts == null ? new int[]{0, 16, 52, 64, 96} : levelCosts.clone();
    }
}
