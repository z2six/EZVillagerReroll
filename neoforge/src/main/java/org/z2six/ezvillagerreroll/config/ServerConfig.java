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

    public static final ModConfigSpec.IntValue FREE_OFFERS;
    public static final ModConfigSpec.IntValue COST_PER_OFFER;
    public static final ModConfigSpec.IntValue MAX_DEDUCTIBLE_LOCKED_OFFERS;

    public static final ModConfigSpec.IntValue AUTO_HOURLY_THRESHOLD;
    public static final ModConfigSpec.DoubleValue AUTO_HOURLY_DISCOUNT_OR_INCREASE_PCT;

    public static final ModConfigSpec.ConfigValue<List<? extends Number>> LEVEL_COSTS;

    // ---------------------------------------------------------------------
    // LIMITS
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.IntValue COOLDOWN_TICKS;       // manual reroll cooldown
    public static final ModConfigSpec.IntValue COOLDOWN_TICKS_AUTO;  // auto-search cooldown
    public static final ModConfigSpec.IntValue PER_VILLAGER_DAILY;

    // ---------------------------------------------------------------------
    // BEHAVIOR
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue ALLOW_AFTER_TRADE_USED;

    // ---------------------------------------------------------------------
    // EXPERIENCE
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue MANUAL_REROLL_XP_PER_OFFER;

    /**
     * Auto-search villager XP gain:
     * XP gained = autoSearchXpPerOffer * (offersRerolledPerReroll) * (successfulRerollCount)
     */
    public static final ModConfigSpec.DoubleValue AUTO_SEARCH_XP_PER_OFFER;

    // ---------------------------------------------------------------------
    // VILLAGER STATS (NEW)
    // These are "effect percent bounds" for converting points [-100..100] into a percent modifier.
    //
    // Recommended mapping:
    //   percent = lerp(minPct, maxPct, (points + 100) / 200.0)
    //
    // Default: -20% .. +20% for each trait.
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue GENEROSITY_MIN_PCT;
    public static final ModConfigSpec.DoubleValue GENEROSITY_MAX_PCT;

    public static final ModConfigSpec.DoubleValue TIMELINESS_MIN_PCT;
    public static final ModConfigSpec.DoubleValue TIMELINESS_MAX_PCT;

    public static final ModConfigSpec.DoubleValue INTELLECT_MIN_PCT;
    public static final ModConfigSpec.DoubleValue INTELLECT_MAX_PCT;

    public static final ModConfigSpec.DoubleValue HOARDER_MIN_PCT;
    public static final ModConfigSpec.DoubleValue HOARDER_MAX_PCT;

    // ---------------------------------------------------------------------
    // SPEC DEFINITION
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

        FREE_OFFERS =
                B.comment("""
                        Number of trade offers that are free (no cost).
                        Example:
                          - freeOffers=2 means a villager with 2 offers costs 0.
                          - A villager with 4 offers would pay for 2 offers (before lock deductions).
                        """)
                        .defineInRange("freeOffers", 2, 0, 64);

        COST_PER_OFFER =
                B.comment("""
                        Cost per paid offer (after freeOffers and lock deduction logic).
                        Total cost = paidOffers * costPerOffer
                        """)
                        .defineInRange("costPerOffer", 8, 0, 640);

        MAX_DEDUCTIBLE_LOCKED_OFFERS =
                B.comment("""
                        Maximum number of locked offers that can reduce the cost computation.
                        Example:
                          - maxDeductibleLockedOffers=1 means locking 10 offers only deducts 1 from the cost calculation.
                        """)
                        .defineInRange("maxDeductibleLockedOffers", 1, 0, 63);

        AUTO_HOURLY_THRESHOLD =
                B.comment("""
                        Auto-search hourly price scaling threshold based on "effective paid offers":
                          effectivePaidOffers = max(0, offers - freeOffers - min(lockedOffers, maxDeductibleLockedOffers))
                        
                        If effectivePaidOffers is BELOW this threshold, auto-search becomes more expensive.
                        If effectivePaidOffers is ABOVE this threshold, auto-search becomes cheaper.
                        
                        The per-step percent is configured via autoHourlyDiscountOrIncreasePct.
                        """)
                        .defineInRange("autoHourlyThreshold", 6, 0, 64);

        AUTO_HOURLY_DISCOUNT_OR_INCREASE_PCT =
                B.comment("""
                        Percent per step (difference between effectivePaidOffers and autoHourlyThreshold).
                        
                        Examples (threshold=6, pct=5):
                          - effectivePaidOffers=2 => below by 4 steps => +20%
                          - effectivePaidOffers=8 => above by 2 steps => -10%
                        
                        This affects the HOURLY cost preview and later settlement.
                        """)
                        .defineInRange("autoHourlyDiscountOrIncreasePct", 5.0, 0.0, 100.0);

        LEVEL_COSTS =
                B.comment("""
                        LEGACY (not used by current cost model).
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
                B.comment("Cooldown in ticks per villager for MANUAL reroll (0 = disabled)")
                        .defineInRange("cooldownTicks", 100, 0, 20_000);

        COOLDOWN_TICKS_AUTO =
                B.comment("Cooldown in ticks per villager for AUTO-SEARCH reroll (0 = disabled)")
                        .defineInRange("cooldownTicksAuto", 600, 0, 20_000);

        PER_VILLAGER_DAILY =
                B.comment("Max rerolls per villager per day (0 = disabled)")
                        .defineInRange("perVillagerDaily", 0, 0, 100);

        B.pop();

        B.push("behavior");

        ALLOW_AFTER_TRADE_USED =
                B.comment("Allow reroll after villager has XP / trades used.")
                        .define("allowAfterTradeUsed", true);

        B.pop();

        B.push("experience");

        MANUAL_REROLL_XP_PER_OFFER =
                B.comment("""
                        Villager XP to grant per offer that is actually rerolled during a MANUAL reroll.
                        
                        XP granted = manualRerollXpPerOffer * (offersRerolled)
                        offersRerolled = totalOffersBefore - lockedOffersCount
                        
                        Set to 0 to disable XP gain from manual rerolls.
                        """)
                        .defineInRange("manualRerollXpPerOffer", 1.0, 0.0, 10_000.0);

        AUTO_SEARCH_XP_PER_OFFER =
                B.comment("""
                        Villager XP to grant per offer-per-reroll during AUTO-SEARCH.
                        
                        XP granted = autoSearchXpPerOffer * (offersRerolledPerReroll) * (successfulRerollCount)
                        offersRerolledPerReroll = offersAtStart - lockedOffersAtStart
                        
                        Set to 0 to disable XP gain from auto-search.
                        """)
                        .defineInRange("autoSearchXpPerOffer", 0.1, 0.0, 10_000.0);

        B.pop();

        // ----------------------------
        // NEW: Villager stats bounds
        // ----------------------------
        B.push("villagerStats");

        GENEROSITY_MIN_PCT =
                B.comment("""
                        Generosity effect MIN percent at points = -100.
                        Default -20 means a fully negative villager increases cost by 20% (if you map points -> percent linearly).
                        """)
                        .defineInRange("generosityMinPct", -20.0, -1000.0, 1000.0);

        GENEROSITY_MAX_PCT =
                B.comment("""
                        Generosity effect MAX percent at points = +100.
                        Default +20 means a fully positive villager reduces cost by 20% (if you map points -> percent linearly).
                        """)
                        .defineInRange("generosityMaxPct", 20.0, -1000.0, 1000.0);

        TIMELINESS_MIN_PCT =
                B.comment("""
                        Timeliness effect MIN percent at points = -100.
                        This will later modify cooldown speed (negative = slower).
                        """)
                        .defineInRange("timelinessMinPct", -20.0, -1000.0, 1000.0);

        TIMELINESS_MAX_PCT =
                B.comment("""
                        Timeliness effect MAX percent at points = +100.
                        This will later modify cooldown speed (positive = faster).
                        """)
                        .defineInRange("timelinessMaxPct", 20.0, -1000.0, 1000.0);

        INTELLECT_MIN_PCT =
                B.comment("""
                        Intellect effect MIN percent at points = -100.
                        This will later modify XP gained (negative = less XP).
                        """)
                        .defineInRange("intellectMinPct", -20.0, -1000.0, 1000.0);

        INTELLECT_MAX_PCT =
                B.comment("""
                        Intellect effect MAX percent at points = +100.
                        This will later modify XP gained (positive = more XP).
                        """)
                        .defineInRange("intellectMaxPct", 20.0, -1000.0, 1000.0);

        HOARDER_MIN_PCT =
                B.comment("""
                        Hoarder effect MIN percent at points = -100.
                        This will later modify offer slot behavior (negative = fewer / constrained).
                        """)
                        .defineInRange("hoarderMinPct", -20.0, -1000.0, 1000.0);

        HOARDER_MAX_PCT =
                B.comment("""
                        Hoarder effect MAX percent at points = +100.
                        This will later modify offer slot behavior (positive = more / expanded).
                        """)
                        .defineInRange("hoarderMaxPct", 20.0, -1000.0, 1000.0);

        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    // ---------------------------------------------------------------------
    // Runtime values
    // ---------------------------------------------------------------------

    public static String costSpec = "minecraft:emerald";
    public static boolean preferWallet = true;
    public static boolean autoPreferLCIfPresent = true;

    public static int freeOffers = 2;
    public static int costPerOffer = 8;
    public static int maxDeductibleLockedOffers = 1;

    public static int autoHourlyThreshold = 6;
    public static double autoHourlyDiscountOrIncreasePct = 5.0;

    public static int cooldownTicks = 200;
    public static int cooldownTicksAuto = 40;
    public static int perVillagerDaily = 0;
    public static boolean allowAfterTradeUsed = true;

    public static double manualRerollXpPerOffer = 1.0;
    public static double autoSearchXpPerOffer = 0.1;

    // NEW: trait effect bounds (%)
    public static double generosityMinPct = -20.0;
    public static double generosityMaxPct = 20.0;

    public static double timelinessMinPct = -20.0;
    public static double timelinessMaxPct = 20.0;

    public static double intellectMinPct = -20.0;
    public static double intellectMaxPct = 20.0;

    public static double hoarderMinPct = -20.0;
    public static double hoarderMaxPct = 20.0;

    private static int[] levelCosts = new int[]{0, 16, 52, 64, 96};

    private static volatile int cfgVersion = 1;
    private static volatile int cfgHash = 0;

    private ServerConfig() {}

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

            freeOffers = Math.max(0, FREE_OFFERS.get());
            costPerOffer = Math.max(0, COST_PER_OFFER.get());
            maxDeductibleLockedOffers = Math.max(0, MAX_DEDUCTIBLE_LOCKED_OFFERS.get());

            autoHourlyThreshold = Math.max(0, AUTO_HOURLY_THRESHOLD.get());
            autoHourlyDiscountOrIncreasePct = Math.max(0.0, AUTO_HOURLY_DISCOUNT_OR_INCREASE_PCT.get());

            cooldownTicks = Math.max(0, COOLDOWN_TICKS.get());
            cooldownTicksAuto = Math.max(0, COOLDOWN_TICKS_AUTO.get());
            perVillagerDaily = Math.max(0, PER_VILLAGER_DAILY.get());
            allowAfterTradeUsed = ALLOW_AFTER_TRADE_USED.get();

            manualRerollXpPerOffer = Math.max(0.0, MANUAL_REROLL_XP_PER_OFFER.get());
            autoSearchXpPerOffer = Math.max(0.0, AUTO_SEARCH_XP_PER_OFFER.get());

            // NEW: trait bounds (normalize min/max so min<=max even if user misconfigures)
            double gMin = GENEROSITY_MIN_PCT.get();
            double gMax = GENEROSITY_MAX_PCT.get();
            double[] gg = normalizeMinMax(gMin, gMax);
            generosityMinPct = gg[0];
            generosityMaxPct = gg[1];

            double tMin = TIMELINESS_MIN_PCT.get();
            double tMax = TIMELINESS_MAX_PCT.get();
            double[] tt = normalizeMinMax(tMin, tMax);
            timelinessMinPct = tt[0];
            timelinessMaxPct = tt[1];

            double iMin = INTELLECT_MIN_PCT.get();
            double iMax = INTELLECT_MAX_PCT.get();
            double[] ii = normalizeMinMax(iMin, iMax);
            intellectMinPct = ii[0];
            intellectMaxPct = ii[1];

            double hMin = HOARDER_MIN_PCT.get();
            double hMax = HOARDER_MAX_PCT.get();
            double[] hh = normalizeMinMax(hMin, hMax);
            hoarderMinPct = hh[0];
            hoarderMaxPct = hh[1];

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
                    "[EZVR] ServerConfig {} OK | v={} hash={} costSpec='{}' preferWallet={} freeOffers={} costPerOffer={} maxDeductibleLockedOffers={} autoHourlyThreshold={} autoHourlyDiscountOrIncreasePct={} cooldownTicks={} cooldownTicksAuto={} perVillagerDaily={} allowAfterTradeUsed={} manualRerollXpPerOffer={} autoSearchXpPerOffer={} traitBounds={}/{} {}/{} {}/{} {}/{} {}/{} legacyLevelCosts={}",
                    reason, cfgVersion, cfgHash,
                    costSpec, preferWallet,
                    freeOffers, costPerOffer, maxDeductibleLockedOffers,
                    autoHourlyThreshold, autoHourlyDiscountOrIncreasePct,
                    cooldownTicks, cooldownTicksAuto, perVillagerDaily, allowAfterTradeUsed,
                    manualRerollXpPerOffer, autoSearchXpPerOffer,
                    // bounds summary
                    generosityMinPct, generosityMaxPct,
                    timelinessMinPct, timelinessMaxPct,
                    intellectMinPct, intellectMaxPct,
                    hoarderMinPct, hoarderMaxPct,
                    debug(levelCosts)
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ServerConfig reload failed", t);
        }
    }

    private static double[] normalizeMinMax(double min, double max) {
        if (Double.isNaN(min)) min = 0.0;
        if (Double.isNaN(max)) max = 0.0;
        if (min <= max) return new double[]{min, max};
        return new double[]{max, min};
    }

    public static int[] costsByLevel5() {
        int[] out = new int[5];
        System.arraycopy(levelCosts, 0, out, 0, Math.min(levelCosts.length, 5));
        return out;
    }

    public static int costForVillagerLevel(int level) {
        int l = Math.max(1, Math.min(5, level));
        return levelCosts[l - 1];
    }

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
        h = 31 * h + (autoPreferLCIfPresent ? 1 : 0);

        h = 31 * h + freeOffers;
        h = 31 * h + costPerOffer;
        h = 31 * h + maxDeductibleLockedOffers;

        h = 31 * h + autoHourlyThreshold;
        long pctBits = Double.doubleToLongBits(autoHourlyDiscountOrIncreasePct);
        h = 31 * h + (int) (pctBits ^ (pctBits >>> 32));

        h = 31 * h + cooldownTicks;
        h = 31 * h + cooldownTicksAuto;
        h = 31 * h + perVillagerDaily;
        h = 31 * h + (allowAfterTradeUsed ? 1 : 0);

        long mBits = Double.doubleToLongBits(manualRerollXpPerOffer);
        h = 31 * h + (int) (mBits ^ (mBits >>> 32));

        long aBits = Double.doubleToLongBits(autoSearchXpPerOffer);
        h = 31 * h + (int) (aBits ^ (aBits >>> 32));

        // NEW: trait bounds
        h = 31 * h + hashD(generosityMinPct);
        h = 31 * h + hashD(generosityMaxPct);

        h = 31 * h + hashD(timelinessMinPct);
        h = 31 * h + hashD(timelinessMaxPct);

        h = 31 * h + hashD(intellectMinPct);
        h = 31 * h + hashD(intellectMaxPct);

        h = 31 * h + hashD(hoarderMinPct);
        h = 31 * h + hashD(hoarderMaxPct);

        for (int v : levelCosts) h = 31 * h + v;
        return h;
    }

    private static int hashD(double d) {
        long bits = Double.doubleToLongBits(d);
        return (int) (bits ^ (bits >>> 32));
    }

    private static String debug(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.append(']').toString();
    }

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
