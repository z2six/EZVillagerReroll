package org.z2six.villageroverhaul.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.villageroverhaul.VillagerOverhaul;

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
    // RECRUIT (NEW)
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.IntValue RECRUIT_COST_MIN;
    public static final ModConfigSpec.IntValue RECRUIT_COST_MAX;

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
    // VILLAGER STATS (merchant traits)
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue GENEROSITY_MIN_PCT;
    public static final ModConfigSpec.DoubleValue GENEROSITY_MAX_PCT;

    public static final ModConfigSpec.DoubleValue TIMELINESS_MIN_PCT;
    public static final ModConfigSpec.DoubleValue TIMELINESS_MAX_PCT;

    public static final ModConfigSpec.DoubleValue INTELLECT_MIN_PCT;
    public static final ModConfigSpec.DoubleValue INTELLECT_MAX_PCT;

    // ---------------------------------------------------------------------
    // COMBAT STATS (NEW)
    // ---------------------------------------------------------------------
    // These are the real-value ranges that points (-100..100) map into.
    // We keep them intentionally generic: they are *deltas* we apply as attribute modifiers later.
    //
    // Vitality -> generic.max_health (ADD_VALUE, in "health points", 2 = 1 heart)
    // Agility  -> generic.movement_speed (ADD_VALUE, typical values ~0.0 - 0.2)
    // Strength -> generic.attack_damage (ADD_VALUE)
    // Armor    -> generic.armor (ADD_VALUE)
    //

    public static final ModConfigSpec.DoubleValue VITALITY_MIN_HEALTH;
    public static final ModConfigSpec.DoubleValue VITALITY_MAX_HEALTH;

    public static final ModConfigSpec.DoubleValue AGILITY_MIN_SPEED;
    public static final ModConfigSpec.DoubleValue AGILITY_MAX_SPEED;

    public static final ModConfigSpec.DoubleValue STRENGTH_MIN_DAMAGE;
    public static final ModConfigSpec.DoubleValue STRENGTH_MAX_DAMAGE;

    public static final ModConfigSpec.DoubleValue ARMOR_MIN;
    public static final ModConfigSpec.DoubleValue ARMOR_MAX;

    // ---------------------------------------------------------------------
    // HOARDER (offer delta clamp)
    // ---------------------------------------------------------------------

    public static final ModConfigSpec.IntValue HOARDER_EXTRA_OFFERS_MIN;
    public static final ModConfigSpec.IntValue HOARDER_EXTRA_OFFERS_MAX;

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

        // ----------------------------
        // Recruit config
        // ----------------------------
        B.push("recruit");

        RECRUIT_COST_MIN =
                B.comment("""
                        Minimum possible recruit cost for an unemployed villager.
                        Cost is computed from villager stats and then normalized into [min..max].
                        """)
                        .defineInRange("recruitCostMin", 8, 0, 64_000);

        RECRUIT_COST_MAX =
                B.comment("""
                        Maximum possible recruit cost for an unemployed villager.
                        Cost is computed from villager stats and then normalized into [min..max].
                        """)
                        .defineInRange("recruitCostMax", 64, 0, 64_000);

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
        // Villager stats bounds
        // ----------------------------
        B.push("villagerStats");

        GENEROSITY_MIN_PCT =
                B.comment("""
                        Generosity effect MIN percent at points = -100.
                        """)
                        .defineInRange("generosityMinPct", -20.0, -1000.0, 1000.0);

        GENEROSITY_MAX_PCT =
                B.comment("""
                        Generosity effect MAX percent at points = +100.
                        """)
                        .defineInRange("generosityMaxPct", 20.0, -1000.0, 1000.0);

        TIMELINESS_MIN_PCT =
                B.comment("""
                        Timeliness effect MIN percent at points = -100.
                        """)
                        .defineInRange("timelinessMinPct", -20.0, -1000.0, 1000.0);

        TIMELINESS_MAX_PCT =
                B.comment("""
                        Timeliness effect MAX percent at points = +100.
                        """)
                        .defineInRange("timelinessMaxPct", 20.0, -1000.0, 1000.0);

        INTELLECT_MIN_PCT =
                B.comment("""
                        Intellect effect MIN percent at points = -100.
                        """)
                        .defineInRange("intellectMinPct", -20.0, -1000.0, 1000.0);

        INTELLECT_MAX_PCT =
                B.comment("""
                        Intellect effect MAX percent at points = +100.
                        """)
                        .defineInRange("intellectMaxPct", 20.0, -1000.0, 1000.0);

        // ----------------------------
        // NEW: Combat stat bounds
        // ----------------------------

        VITALITY_MIN_HEALTH =
                B.comment("""
                        Vitality MIN delta applied to generic.max_health at points=-100.
                        Unit: health points (2.0 = 1 heart).
                        Example: -6.0 means -3 hearts.
                        """)
                        .defineInRange("vitalityMinHealth", -6.0, -1024.0, 1024.0);

        VITALITY_MAX_HEALTH =
                B.comment("""
                        Vitality MAX delta applied to generic.max_health at points=+100.
                        Unit: health points (2.0 = 1 heart).
                        Example: +10.0 means +5 hearts.
                        """)
                        .defineInRange("vitalityMaxHealth", 10.0, -1024.0, 1024.0);

        AGILITY_MIN_SPEED =
                B.comment("""
                        Agility MIN delta applied to generic.movement_speed at points=-100.
                        Unit: raw movement_speed additive value.
                        Typical base values are around 0.1; keep changes small.
                        """)
                        .defineInRange("agilityMinSpeed", -0.02, -1.0, 1.0);

        AGILITY_MAX_SPEED =
                B.comment("""
                        Agility MAX delta applied to generic.movement_speed at points=+100.
                        Unit: raw movement_speed additive value.
                        """)
                        .defineInRange("agilityMaxSpeed", 0.03, -1.0, 1.0);

        STRENGTH_MIN_DAMAGE =
                B.comment("""
                        Strength MIN delta applied to generic.attack_damage at points=-100.
                        Unit: damage points.
                        """)
                        .defineInRange("strengthMinDamage", -1.0, -1024.0, 1024.0);

        STRENGTH_MAX_DAMAGE =
                B.comment("""
                        Strength MAX delta applied to generic.attack_damage at points=+100.
                        Unit: damage points.
                        """)
                        .defineInRange("strengthMaxDamage", 3.0, -1024.0, 1024.0);

        ARMOR_MIN =
                B.comment("""
                        Armor MIN delta applied to generic.armor at points=-100.
                        Unit: armor points.
                        """)
                        .defineInRange("armorMin", -5.0, -1024.0, 1024.0);

        ARMOR_MAX =
                B.comment("""
                        Armor MAX delta applied to generic.armor at points=+100.
                        Unit: armor points.
                        """)
                        .defineInRange("armorMax", 15.0, -1024.0, 1024.0);

        // ----------------------------
        // Hoarder clamp
        // ----------------------------

        HOARDER_EXTRA_OFFERS_MIN =
                B.comment("""
                        Hoarder offer DELTA clamp MIN (applied to hoarder points).
                        """)
                        .defineInRange("hoarderExtraOffersMin", -3, -64, 64);

        HOARDER_EXTRA_OFFERS_MAX =
                B.comment("""
                        Hoarder offer DELTA clamp MAX (applied to hoarder points).
                        """)
                        .defineInRange("hoarderExtraOffersMax", 3, -64, 64);

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

    public static int recruitCostMin = 8;
    public static int recruitCostMax = 64;

    public static int cooldownTicks = 100;
    public static int cooldownTicksAuto = 600;

    public static int perVillagerDaily = 0;
    public static boolean allowAfterTradeUsed = true;

    public static double manualRerollXpPerOffer = 1.0;
    public static double autoSearchXpPerOffer = 0.1;

    public static double generosityMinPct = -20.0;
    public static double generosityMaxPct = 20.0;

    public static double timelinessMinPct = -20.0;
    public static double timelinessMaxPct = 20.0;

    public static double intellectMinPct = -20.0;
    public static double intellectMaxPct = 20.0;

    // NEW: combat bounds
    public static double vitalityMinHealth = -6.0;
    public static double vitalityMaxHealth = 10.0;

    public static double agilityMinSpeed = -0.02;
    public static double agilityMaxSpeed = 0.03;

    public static double strengthMinDamage = -1.0;
    public static double strengthMaxDamage = 3.0;

    public static double armorMin = -5.0;
    public static double armorMax = 15.0;

    public static int hoarderExtraOffersMin = -3;
    public static int hoarderExtraOffersMax = 3;

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

            int rMin = Math.max(0, RECRUIT_COST_MIN.get());
            int rMax = Math.max(0, RECRUIT_COST_MAX.get());
            if (rMin > rMax) { int tmp = rMin; rMin = rMax; rMax = tmp; }
            recruitCostMin = rMin;
            recruitCostMax = rMax;

            cooldownTicks = Math.max(0, COOLDOWN_TICKS.get());
            cooldownTicksAuto = Math.max(0, COOLDOWN_TICKS_AUTO.get());
            perVillagerDaily = Math.max(0, PER_VILLAGER_DAILY.get());
            allowAfterTradeUsed = ALLOW_AFTER_TRADE_USED.get();

            manualRerollXpPerOffer = Math.max(0.0, MANUAL_REROLL_XP_PER_OFFER.get());
            autoSearchXpPerOffer = Math.max(0.0, AUTO_SEARCH_XP_PER_OFFER.get());

            double[] gg = normalizeMinMax(GENEROSITY_MIN_PCT.get(), GENEROSITY_MAX_PCT.get());
            generosityMinPct = gg[0];
            generosityMaxPct = gg[1];

            double[] tt = normalizeMinMax(TIMELINESS_MIN_PCT.get(), TIMELINESS_MAX_PCT.get());
            timelinessMinPct = tt[0];
            timelinessMaxPct = tt[1];

            double[] ii = normalizeMinMax(INTELLECT_MIN_PCT.get(), INTELLECT_MAX_PCT.get());
            intellectMinPct = ii[0];
            intellectMaxPct = ii[1];

            // NEW: combat bounds (normalize each pair)
            double[] vh = normalizeMinMax(VITALITY_MIN_HEALTH.get(), VITALITY_MAX_HEALTH.get());
            vitalityMinHealth = vh[0];
            vitalityMaxHealth = vh[1];

            double[] as = normalizeMinMax(AGILITY_MIN_SPEED.get(), AGILITY_MAX_SPEED.get());
            agilityMinSpeed = as[0];
            agilityMaxSpeed = as[1];

            double[] sd = normalizeMinMax(STRENGTH_MIN_DAMAGE.get(), STRENGTH_MAX_DAMAGE.get());
            strengthMinDamage = sd[0];
            strengthMaxDamage = sd[1];

            double[] ar = normalizeMinMax(ARMOR_MIN.get(), ARMOR_MAX.get());
            armorMin = ar[0];
            armorMax = ar[1];

            int hMin = HOARDER_EXTRA_OFFERS_MIN.get();
            int hMax = HOARDER_EXTRA_OFFERS_MAX.get();
            if (hMin > hMax) { int tmp = hMin; hMin = hMax; hMax = tmp; }
            hoarderExtraOffersMin = hMin;
            hoarderExtraOffersMax = hMax;

            levelCosts = parseLevelCosts(LEVEL_COSTS.get());

            if (autoPreferLCIfPresent
                    && ModList.get().isLoaded("lightmanscurrency")
                    && "minecraft:emerald".equals(costSpec)) {
                costSpec = "lightmanscurrency:coin_emerald";
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Auto-switched cost to LC emerald coin");
            }

            cfgVersion++;
            cfgHash = computeHash();

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] ServerConfig {} OK | v={} hash={} costSpec='{}' preferWallet={} freeOffers={} costPerOffer={} maxDeductibleLockedOffers={} autoHourlyThreshold={} autoHourlyDiscountOrIncreasePct={} recruitCost=[{},{}] cooldownTicks={} cooldownTicksAuto={} perVillagerDaily={} allowAfterTradeUsed={} manualRerollXpPerOffer={} autoSearchXpPerOffer={} traitBounds={}/{} {}/{} {}/{} combatBounds=vitality[{}/{}] agility[{}/{}] strength[{}/{}] armor[{}/{}] hoarderClamp=[{},{}] legacyLevelCosts={}",
                    reason, cfgVersion, cfgHash,
                    costSpec, preferWallet,
                    freeOffers, costPerOffer, maxDeductibleLockedOffers,
                    autoHourlyThreshold, autoHourlyDiscountOrIncreasePct,
                    recruitCostMin, recruitCostMax,
                    cooldownTicks, cooldownTicksAuto, perVillagerDaily, allowAfterTradeUsed,
                    manualRerollXpPerOffer, autoSearchXpPerOffer,
                    generosityMinPct, generosityMaxPct,
                    timelinessMinPct, timelinessMaxPct,
                    intellectMinPct, intellectMaxPct,
                    vitalityMinHealth, vitalityMaxHealth,
                    agilityMinSpeed, agilityMaxSpeed,
                    strengthMinDamage, strengthMaxDamage,
                    armorMin, armorMax,
                    hoarderExtraOffersMin, hoarderExtraOffersMax,
                    debug(levelCosts)
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ServerConfig reload failed", t);
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

        h = 31 * h + recruitCostMin;
        h = 31 * h + recruitCostMax;

        h = 31 * h + cooldownTicks;
        h = 31 * h + cooldownTicksAuto;
        h = 31 * h + perVillagerDaily;
        h = 31 * h + (allowAfterTradeUsed ? 1 : 0);

        long mBits = Double.doubleToLongBits(manualRerollXpPerOffer);
        h = 31 * h + (int) (mBits ^ (mBits >>> 32));

        long aBits = Double.doubleToLongBits(autoSearchXpPerOffer);
        h = 31 * h + (int) (aBits ^ (aBits >>> 32));

        h = 31 * h + hashD(generosityMinPct);
        h = 31 * h + hashD(generosityMaxPct);

        h = 31 * h + hashD(timelinessMinPct);
        h = 31 * h + hashD(timelinessMaxPct);

        h = 31 * h + hashD(intellectMinPct);
        h = 31 * h + hashD(intellectMaxPct);

        // NEW: combat bounds
        h = 31 * h + hashD(vitalityMinHealth);
        h = 31 * h + hashD(vitalityMaxHealth);

        h = 31 * h + hashD(agilityMinSpeed);
        h = 31 * h + hashD(agilityMaxSpeed);

        h = 31 * h + hashD(strengthMinDamage);
        h = 31 * h + hashD(strengthMaxDamage);

        h = 31 * h + hashD(armorMin);
        h = 31 * h + hashD(armorMax);

        h = 31 * h + hoarderExtraOffersMin;
        h = 31 * h + hoarderExtraOffersMax;

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
