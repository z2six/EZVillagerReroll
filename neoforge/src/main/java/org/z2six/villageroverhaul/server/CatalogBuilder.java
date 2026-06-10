// neoforge\src\main\java\org\z2six\villageroverhaul\server\CatalogBuilder.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Builds a catalog of "possible outputs" a villager could offer based on the
 * exact VillagerTrades.TRADES pool (profession -> level -> ItemListing[]).
 *
 * IMPORTANT DESIGN CHOICES (per your requirements):
 * - No profession-specific special casing (we never check for LIBRARIAN).
 * - No sampling loops. Each listing is read deterministically:
 *   - If the listing is deterministic: we call getOffer() once (seeded RNG) and collect its result.
 *   - If the listing is known-randomized (EnchantBookForEmeralds): we expand by enumerating ALL enchantments
 *     and all their levels in the enchantment registry, but only for enchantments in EnchantmentTags.TRADEABLE.
 *
 * Notes:
 * - VillagerTrades.ItemListing is the *source of truth* for what can be generated.
 * - Some modded listings may randomize outputs in ways we cannot enumerate without mod-specific code.
 *   In those cases, we still include at least one representative output and log a debug message.
 */
public final class CatalogBuilder {

    private static final int MAX_TOTAL_ITEMS = 16384;

    // Cache reflective access to EnchantmentTags.TRADEABLE + Holder#is(TagKey)
    private static volatile boolean TAG_REFLECTION_LOOKED_UP = false;
    private static volatile Object TAG_TRADEABLE = null;     // TagKey<Enchantment> (as Object)
    private static volatile Method HOLDER_IS_TAGKEY = null;  // Holder#is(TagKey)
    private static volatile boolean LOGGED_TAG_LOOKUP = false;

    private CatalogBuilder() {}

    /**
     * Generate a single additional offer for the given villager and level using the same ItemListing pool vanilla uses.
     * This is used by Hoarder extra-offer logic to add more trades WITHOUT calling updateTrades() (which can overwrite existing locked offers).
     */
    public static MerchantOffer generateAdditionalOfferForLevel(Villager vill, int lvl) {
        try {
            if (vill == null) return null;

            VillagerData vd = vill.getVillagerData();
            VillagerProfession prof = vd.getProfession();
            int level = Math.max(1, Math.min(5, lvl));

            if (prof == null) return null;

            Object byProfession = resolveTradesByProfession(prof);
            if (byProfession == null) return null;

            VillagerTrades.ItemListing[] listings = getListingsForLevel(byProfession, level);
            if (listings == null || listings.length == 0) return null;

            RandomSource rand;
            try { rand = vill.getRandom(); } catch (Throwable ignored) { rand = RandomSource.create(); }

            int start = Math.floorMod(rand.nextInt(), listings.length);
            for (int i = 0; i < listings.length; i++) {
                VillagerTrades.ItemListing listing;
                try {
                    listing = listings[(start + i) % listings.length];
                } catch (Throwable t) {
                    listing = null;
                }
                if (listing == null) continue;

                MerchantOffer offer = safeGetOfferRandom(listing, vill, rand);
                if (offer == null) continue;

                ItemStack res = ItemStack.EMPTY;
                try { res = offer.getResult(); } catch (Throwable ignored) {}
                if (res == null || res.isEmpty()) continue;

                return offer;
            }

            return null;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.generateAdditionalOfferForLevel failed (soft): {}", t.toString());
            return null;
        }
    }

    public static List<ItemStack> buildCatalog(Villager vill) {
        try {
            if (vill == null) return List.of();

            VillagerData vd = vill.getVillagerData();
            VillagerProfession prof = vd.getProfession();
            int level = Math.max(1, Math.min(5, vd.getLevel()));

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.buildCatalog: villager={} prof={} level={}",
                    vill.getUUID(), prof == null ? "null" : String.valueOf(prof), level);

            // unique outputs by key (item + components patch)
            Map<String, ItemStack> unique = new LinkedHashMap<>();

            // Include CURRENT offers too (helps when a listing is randomized and we only take one representative output)
            try {
                MerchantOffers offers = vill.getOffers();
                if (offers != null) {
                    for (MerchantOffer o : offers) {
                        if (o == null) continue;
                        ItemStack out = o.getResult();
                        if (out == null || out.isEmpty()) continue;
                        unique.putIfAbsent(keyOf(out), out.copy());
                    }
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder: failed reading current offers (soft): {}", t.toString());
            }

            // Core: read the exact TRADES pool, level 1..current level
            addTradesFromVillagerTrades(vill, prof, level, unique);

            List<ItemStack> out = new ArrayList<>(unique.values());
            if (out.size() > MAX_TOTAL_ITEMS) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder.buildCatalog: truncating catalog {} -> MAX_TOTAL_ITEMS={}",
                        out.size(), MAX_TOTAL_ITEMS);
                out = out.subList(0, MAX_TOTAL_ITEMS);
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.buildCatalog: villager={} catalogSize={}",
                    vill.getUUID(), out.size());

            return out;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] CatalogBuilder.buildCatalog failed", t);
            return List.of();
        }
    }

    private static void addTradesFromVillagerTrades(
            Villager vill,
            VillagerProfession prof,
            int level,
            Map<String, ItemStack> unique
    ) {
        try {
            if (vill == null) return;
            if (prof == null) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder.addTradesFromVillagerTrades: prof=null; cannot read VillagerTrades.TRADES.");
                return;
            }

            Object byProfession = resolveTradesByProfession(prof);
            if (byProfession == null) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder: VillagerTrades.TRADES has no entry for prof={}", prof);
                return;
            }

            int before = unique.size();
            int[] listingCount = new int[] {0};
            int[] offerCount = new int[] {0};
            int[] bookExpanded = new int[] {0};
            int[] bookAdded = new int[] {0};

            for (int lvl = 1; lvl <= level; lvl++) {
                VillagerTrades.ItemListing[] listings = getListingsForLevel(byProfession, lvl);
                if (listings == null || listings.length == 0) continue;

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder: prof={} lvl={} listings={}", prof, lvl, listings.length);

                for (VillagerTrades.ItemListing listing : listings) {
                    if (listing == null) continue;
                    listingCount[0]++;

                    if (unique.size() >= MAX_TOTAL_ITEMS) {
                        VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder: reached MAX_TOTAL_ITEMS={} while reading listings; stopping.", MAX_TOTAL_ITEMS);
                        break;
                    }

                    if (isEnchantBookForEmeraldsListing(listing)) {
                        // Enumerate all possible enchanted books (all TRADEABLE enchants + all levels).
                        int added = expandEnchantedBookListing(vill, unique);
                        bookExpanded[0]++;
                        if (added >= 0) {
                            bookAdded[0] += Math.max(0, added);
                        } else {
                            // If we can't reliably enumerate (tag/method not available), fall back to one representative offer.
                            MerchantOffer offer = safeGetOfferOnce(listing, vill, lvl);
                            if (offer != null) {
                                ItemStack res = offer.getResult();
                                if (res != null && !res.isEmpty()) {
                                    unique.putIfAbsent(keyOf(res), res.copy());
                                }
                            }
                        }
                        continue;
                    }

                    // Deterministic single-offer read (no sampling loops).
                    MerchantOffer offer = safeGetOfferOnce(listing, vill, lvl);
                    if (offer == null) continue;
                    offerCount[0]++;

                    ItemStack res = offer.getResult();
                    if (res == null || res.isEmpty()) continue;

                    unique.putIfAbsent(keyOf(res), res.copy());
                }
            }

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] CatalogBuilder.addTradesFromVillagerTrades: prof={} level=1..{} listingsSeen={} offersRead={} bookListingsExpanded={} bookItemsAdded={} size {}->{}",
                    String.valueOf(prof), level, listingCount[0], offerCount[0], bookExpanded[0], bookAdded[0], before, unique.size()
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] CatalogBuilder.addTradesFromVillagerTrades failed", t);
        }
    }

    private static Object resolveTradesByProfession(VillagerProfession prof) {
        try {
            Object byProfession = null;

            try {
                byProfession = VillagerTrades.TRADES.get(prof);
            } catch (Throwable ignored) {}

            if (byProfession == null) {
                try {
                    for (Object entryObj : VillagerTrades.TRADES.entrySet()) {
                        if (!(entryObj instanceof Map.Entry<?, ?> en)) continue;
                        Object k = en.getKey();
                        if (k == prof) {
                            byProfession = en.getValue();
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            return byProfession;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.resolveTradesByProfession failed (soft): {}", t.toString());
            return null;
        }
    }

    private static VillagerTrades.ItemListing[] getListingsForLevel(Object byProfession, int lvl) {
        try {
            if (byProfession == null) return null;

            try {
                Method mGet = byProfession.getClass().getMethod("get", int.class);
                Object v = mGet.invoke(byProfession, lvl);
                if (v instanceof VillagerTrades.ItemListing[] arr) return arr;
            } catch (NoSuchMethodException ignored) {
                // fallthrough
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.getListingsForLevel reflective get(int) failed (soft): {}", t.toString());
            }

            if (byProfession instanceof Map<?, ?> map) {
                Object v = map.get(lvl);
                if (v instanceof VillagerTrades.ItemListing[] arr) return arr;
            }

            return null;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.getListingsForLevel failed (soft): {}", t.toString());
            return null;
        }
    }

    private static MerchantOffer safeGetOfferOnce(VillagerTrades.ItemListing listing, Villager vill, int lvl) {
        try {
            if (listing == null || vill == null) return null;

            // Seeded RNG => stable output across openings (prevents “different results every time” drift)
            long seed = 0x5EEDL ^ (long) listing.getClass().getName().hashCode() ^ (long) listing.hashCode() ^ ((long) lvl * 31L);
            RandomSource rand = RandomSource.create(seed);

            try {
                return listing.getOffer(vill, rand);
            } catch (Throwable ignored) {}

            // Reflective fallback for modded listings with signature variance
            try {
                for (Method m : listing.getClass().getMethods()) {
                    if (!m.getName().equals("getOffer")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2) {
                        Object offer = m.invoke(listing, vill, rand);
                        return (offer instanceof MerchantOffer mo) ? mo : null;
                    }
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.safeGetOfferOnce reflective fallback failed (soft): {}", t.toString());
            }

            return null;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.safeGetOfferOnce failed (soft): {}", t.toString());
            return null;
        }
    }

    private static MerchantOffer safeGetOfferRandom(VillagerTrades.ItemListing listing, Villager vill, RandomSource rand) {
        try {
            if (listing == null || vill == null || rand == null) return null;

            try {
                return listing.getOffer(vill, rand);
            } catch (Throwable ignored) {}

            try {
                for (Method m : listing.getClass().getMethods()) {
                    if (!m.getName().equals("getOffer")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2) {
                        Object offer = m.invoke(listing, vill, rand);
                        return (offer instanceof MerchantOffer mo) ? mo : null;
                    }
                }
            } catch (Throwable ignored) {}

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isEnchantBookForEmeraldsListing(VillagerTrades.ItemListing listing) {
        try {
            if (listing == null) return false;

            // We avoid direct class references to keep compatibility with mappings/relocations.
            String cn = listing.getClass().getName();
            if (cn == null) return false;

            // Vanilla nested class is typically: net.minecraft.world.entity.npc.VillagerTrades$EnchantBookForEmeralds
            // Some environments may rename, but usually keep “EnchantBookForEmeralds”.
            return cn.contains("EnchantBookForEmeralds");

        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Expands the enchanted-book listing into concrete enchanted book outputs by enumerating the enchantment registry,
     * keeping only enchantments in EnchantmentTags.TRADEABLE (via reflection for mapping resilience).
     *
     * @return how many unique book ItemStacks were newly added to 'unique'
     */
    private static int expandEnchantedBookListing(Villager vill, Map<String, ItemStack> unique) {
        int added = 0;

        try {
            if (vill == null) return 0;
            if (unique == null) return 0;
            if (unique.size() >= MAX_TOTAL_ITEMS) return 0;

            Registry<Enchantment> reg;
            try {
                reg = vill.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder: cannot access enchantment registry; cannot expand EnchantBookForEmeralds.");
                return -1;
            }

            final int before = unique.size();

            ensureEnchantmentTagReflection();
            if (TAG_TRADEABLE == null || HOLDER_IS_TAGKEY == null) {
                // Can't enumerate the real set safely; let caller fall back to a representative offer.
                return -1;
            }

            int[] scanned = new int[] {0};
            int[] enumeratedBooks = new int[] {0};
            int[] capSkips = new int[] {0};
            int[] errSkips = new int[] {0};

            for (Holder<Enchantment> holder : reg.holders().toList()) {
                scanned[0]++;

                if (unique.size() >= MAX_TOTAL_ITEMS) {
                    capSkips[0]++;
                    break;
                }

                if (holder == null) {
                    errSkips[0]++;
                    continue;
                }

                // Must be in TRADEABLE tag
                if (!holderHasEnchantmentTag(holder, TAG_TRADEABLE)) {
                    continue;
                }

                Enchantment ench;
                try {
                    ench = holder.value();
                } catch (Throwable t) {
                    errSkips[0]++;
                    continue;
                }

                int max = 1;
                try {
                    max = Math.max(1, ench.getMaxLevel());
                } catch (Throwable ignoredMax) {
                    max = 1;
                }

                for (int lvl = 1; lvl <= max; lvl++) {
                    if (unique.size() >= MAX_TOTAL_ITEMS) {
                        capSkips[0]++;
                        break;
                    }

                    enumeratedBooks[0]++;

                    ItemStack book;
                    try {
                        book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, lvl));
                    } catch (Throwable t) {
                        errSkips[0]++;
                        continue;
                    }

                    if (book == null || book.isEmpty()) {
                        errSkips[0]++;
                        continue;
                    }

                    String k = keyOf(book);
                    if (unique.putIfAbsent(k, book) == null) {
                        added++;
                    }
                }
            }

            if (VillagerOverhaul.LOG().isDebugEnabled() || added > 0) {
                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] CatalogBuilder: EnchantBookForEmeralds expansion scannedEnchants={} enumeratedBooks={} addedBooks={} capSkips={} errSkips={} size {}->{}",
                        scanned[0], enumeratedBooks[0], added, capSkips[0], errSkips[0],
                        before, unique.size()
                );
            }

            return added;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.expandEnchantedBookListing failed (soft): {}", t.toString());
            return -1;
        }
    }

    private static void ensureEnchantmentTagReflection() {
        try {
            if (TAG_REFLECTION_LOOKED_UP) return;
            TAG_REFLECTION_LOOKED_UP = true;

            // Holder#is(TagKey) method
            try {
                for (Method m : Holder.class.getMethods()) {
                    if (!m.getName().equals("is")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0].getName().equals("net.minecraft.tags.TagKey")) {
                        HOLDER_IS_TAGKEY = m;
                        break;
                    }
                }
            } catch (Throwable t) {
                HOLDER_IS_TAGKEY = null;
            }

            // EnchantmentTags.TRADEABLE (field name)
            try {
                Class<?> clz = Class.forName("net.minecraft.tags.EnchantmentTags");
                TAG_TRADEABLE = readStaticFieldIfPresent(clz, "TRADEABLE");

                if (!LOGGED_TAG_LOOKUP) {
                    LOGGED_TAG_LOOKUP = true;
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder: tag lookup EnchantmentTags.TRADEABLE={} Holder#is(TagKey)={}",
                            TAG_TRADEABLE != null, HOLDER_IS_TAGKEY != null);
                }

            } catch (Throwable t) {
                TAG_TRADEABLE = null;
                if (!LOGGED_TAG_LOOKUP) {
                    LOGGED_TAG_LOOKUP = true;
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder: cannot reflect net.minecraft.tags.EnchantmentTags; enchanted book expansion will fall back to sampling. ({})",
                            t.toString());
                }
            }

        } catch (Throwable t) {
            TAG_TRADEABLE = null;
            HOLDER_IS_TAGKEY = null;

            if (!LOGGED_TAG_LOOKUP) {
                LOGGED_TAG_LOOKUP = true;
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] CatalogBuilder: ensureEnchantmentTagReflection failed; enchanted book expansion will fall back to sampling. ({})",
                        t.toString());
            }
        }
    }

    private static Object readStaticFieldIfPresent(Class<?> clz, String fieldName) {
        try {
            if (clz == null || fieldName == null) return null;
            Field f = clz.getField(fieldName);
            if (f == null) return null;
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean holderHasEnchantmentTag(Holder<Enchantment> holder, Object tagKeyObj) {
        try {
            if (holder == null) return false;
            if (tagKeyObj == null) return false;
            if (HOLDER_IS_TAGKEY == null) return false;

            Object r = HOLDER_IS_TAGKEY.invoke(holder, tagKeyObj);
            return (r instanceof Boolean b) && b.booleanValue();

        } catch (Throwable t) {
            return false;
        }
    }

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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] CatalogBuilder.keyOf failed (soft): {}", t.toString());
            return "err|" + Objects.hashCode(s);
        }
    }
}
