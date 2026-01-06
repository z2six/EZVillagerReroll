// MainFile: src/main/java/org/z2six/ezvillagerreroll/config/ServerConfig.java
package org.z2six.ezvillagerreroll.config;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketSyncConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@EventBusSubscriber(modid = EZVillagerReroll.MODID)
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
                      - any tag: "#minecraft:logs"  (applies to inventory payment only; wallet does not support tags)
                    """)
                    .define("cost.itemOrTag", "minecraft:emerald");

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> COSTS_BY_LEVEL =
            B.comment("""
                    Explicit reroll costs per villager level.
                    Index meanings:
                      - level 0: used for non-villager/unknown in tooltips; recommended 0
                      - level 1..5: villager levels 1..5
                    Example:
                      costsByLevel = [0, 16, 52, 64, 64, 64]
                    """)
                    .defineListAllowEmpty(
                            List.of("costsByLevel"),
                            () -> List.of(0, 16, 52, 64, 64, 64),
                            o -> {
                                if (!(o instanceof Integer i)) return false;
                                return i >= 0 && i <= 100000;
                            }
                    );

    public static final ModConfigSpec.BooleanValue PREFER_WALLET =
            B.comment("""
                    If true and Lightman's Currency is installed, attempt to withdraw the cost
                    from the player's LC Wallet/MoneyAPI first (only when cost.itemOrTag is an exact item, not a tag).
                    If that fails, fall back to inventory.
                    """).define("cost.preferWallet", true);

    public static final ModConfigSpec.BooleanValue AUTO_DEFAULT_LC_IF_PRESENT =
            B.comment("""
                    QoL: If LC is installed and cost.itemOrTag is still "minecraft:emerald",
                    auto-switch to "lightmanscurrency:coin_emerald" on load (logged).
                    """).define("cost.autoPreferLCIfPresent", true);

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

    // Baked values (authoritative on server)
    public static String costSpec = "minecraft:emerald";
    public static List<Integer> costsByLevel = List.of(0, 16, 52, 64, 64, 64);
    public static boolean preferWallet = true;
    public static boolean autoPreferLCIfPresent = true;
    public static int cooldownTicks = 200;
    public static int perVillagerDaily = 0;
    public static boolean allowAfterTradeUsed = true;

    // Version/hash for sync visibility
    private static volatile int cfgVersion = 1;
    private static volatile int cfgHash = 0;

    @net.neoforged.bus.api.SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        try {
            // Only react to our SERVER config (NeoForge fires for all configs)
            if (event.getConfig() == null || event.getConfig().getSpec() != SPEC) return;

            costSpec = COST_ITEM_OR_TAG.get();
            preferWallet = PREFER_WALLET.get();
            autoPreferLCIfPresent = AUTO_DEFAULT_LC_IF_PRESENT.get();
            cooldownTicks = COOLDOWN_TICKS.get();
            perVillagerDaily = PER_VILLAGER_DAILY.get();
            allowAfterTradeUsed = ALLOW_AFTER_TRADE_USED.get();

            // Bake level cost list safely
            List<? extends Integer> raw = COSTS_BY_LEVEL.get();
            List<Integer> baked = new ArrayList<>();
            if (raw != null) {
                for (Object o : raw) {
                    if (o instanceof Integer i && i >= 0) baked.add(i);
                }
            }
            // Ensure size at least 6 (0..5)
            while (baked.size() < 6) baked.add(0);
            // Truncate if larger
            if (baked.size() > 6) baked = baked.subList(0, 6);
            costsByLevel = List.copyOf(baked);

            if (autoPreferLCIfPresent
                    && ModList.get().isLoaded("lightmanscurrency")
                    && "minecraft:emerald".equals(costSpec)) {
                costSpec = "lightmanscurrency:coin_emerald";
                EZVillagerReroll.LOG().info("[EZVR] Lightman's Currency detected. Auto-switching cost.itemOrTag to '{}'", costSpec);
            }

            // Update hash/version (bump version each successful bake; hash deterministic)
            cfgVersion = Math.max(1, cfgVersion + 1);
            cfgHash = Objects.hash(costSpec, costsByLevel, preferWallet, cooldownTicks, perVillagerDaily, allowAfterTradeUsed);

            EZVillagerReroll.LOG().info(
                    "[EZVR] ServerConfig loaded: costSpec='{}', costsByLevel={}, preferWallet={}, cooldownTicks={}, perVillagerDaily={}, allowAfterTradeUsed={}, version={}, hash={}",
                    costSpec, costsByLevel, preferWallet, cooldownTicks, perVillagerDaily, allowAfterTradeUsed, cfgVersion, cfgHash
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig load error", t);
        }
    }

    public static int cfgVersion() {
        return cfgVersion;
    }

    public static int cfgHash() {
        return cfgHash;
    }

    public static boolean isTagSpec(String s) {
        return s != null && s.startsWith("#");
    }

    public static TagKey<Item> asItemTag(String s) {
        if (!isTagSpec(s)) return null;
        ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
        return id == null ? null : TagKey.create(Registries.ITEM, id);
    }

    public static ResourceLocation costItemRLOrNull() {
        try {
            if (isTagSpec(costSpec)) return null;
            return ResourceLocation.tryParse(costSpec);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int costForVillagerLevel(int villagerLevel) {
        int lvl = Math.max(0, Math.min(5, villagerLevel));
        try {
            List<Integer> list = costsByLevel;
            if (list == null || list.size() < 6) return 0;
            Integer v = list.get(lvl);
            return v == null ? 0 : Math.max(0, v);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static PacketSyncConfig snapshotForSync() {
        PacketSyncConfig s = new PacketSyncConfig();
        try {
            s.version = cfgVersion();
            s.hash = cfgHash();
            s.costItemOrTag = costSpec == null ? "minecraft:emerald" : costSpec;
            s.costsByLevel = new int[6];
            for (int i = 0; i < 6; i++) s.costsByLevel[i] = costForVillagerLevel(i);
            s.preferWallet = preferWallet;
            s.cooldownTicks = cooldownTicks;
            s.perVillagerDaily = perVillagerDaily;
            s.allowAfterTradeUsed = allowAfterTradeUsed;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] snapshotForSync error", t);
        }
        return s;
    }

    private ServerConfig() {}
}
