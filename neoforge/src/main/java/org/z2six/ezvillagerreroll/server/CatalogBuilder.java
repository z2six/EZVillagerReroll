// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/CatalogBuilder.java
package org.z2six.ezvillagerreroll.server;

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
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Builds a catalog of "possible outputs" a villager could have, not just current offers.
 *
 * Design goals:
 * - Server-safe.
 * - Includes modded items (by using VillagerTrades / listings).
 * - For randomized listings, sample multiple times to capture possibilities.
 * - Librarian special-case: include registry enchantments (vanilla + modded) for enchanted books,
 *   BUT ONLY those that are actually tradeable (via reflective access to a tradeable-flag method).
 *
 * NOTE (important):
 * There is no universal "enumerate all possible offers" API for all modded merchants.
 * Sampling offer generators is the only general mechanism.
 */
public final class CatalogBuilder {

    // Safety caps
    private static final int MAX_TOTAL_ITEMS = 16384;

    // Enough to capture many random listings without being too heavy
    private static final int MAX_SAMPLE_PER_LISTING = 128;

    // Cap how many levels we enumerate per enchantment (mods can go extreme)
    private static final int MAX_ENCHANTABILITY_LEVEL_ENUM = 20;

    // Cached reflective method for "tradeable" flag.
    // We intentionally avoid calling ench.isTradeable() directly to keep compilation compatible
    // with different mappings / NeoForge versions.
    private static volatile Method CACHED_TRADEABLE_METHOD = null;
    private static volatile boolean LOOKED_UP_TRADEABLE_METHOD = false;
    private static volatile boolean LOGGED_TRADEABLE_LOOKUP_FAILURE = false;

    private CatalogBuilder() {}

    public static List<ItemStack> buildCatalog(Villager vill) {
        try {
            if (vill == null) return List.of();

            VillagerData vd = vill.getVillagerData();
            VillagerProfession prof = vd.getProfession();
            int level = Math.max(1, Math.min(5, vd.getLevel()));

            EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder.buildCatalog: villager={} prof={} level={}",
                    vill.getUUID(), prof == null ? "null" : String.valueOf(prof), level);

            // Collect unique outputs by key (includes components to distinguish e.g. enchanted books)
            Map<String, ItemStack> unique = new LinkedHashMap<>();

            // 1) Include CURRENT offers too
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
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: failed reading current offers (soft): {}", t.toString());
            }

            // 2) Add possible outputs from VillagerTrades listings (sampling)
            addTradesFromVillagerTrades(vill, prof, level, unique);

            // 3) Special-case librarian enchanted books: registry expansion (tradeable-only)
            tryAddAllEnchantedBooksIfLibrarian(vill, prof, unique);

            // Finalize
            List<ItemStack> out = new ArrayList<>(unique.values());
            if (out.size() > MAX_TOTAL_ITEMS) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder.buildCatalog: truncating catalog {} -> MAX_TOTAL_ITEMS={}",
                        out.size(), MAX_TOTAL_ITEMS);
                out = out.subList(0, MAX_TOTAL_ITEMS);
            }

            EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder.buildCatalog: villager={} catalogSize={}",
                    vill.getUUID(), out.size());

            return out;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] CatalogBuilder.buildCatalog failed", t);
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
            if (vill == null || prof == null) return;

            Object byProfession = null;

            try {
                byProfession = VillagerTrades.TRADES.get(prof);
            } catch (Throwable ignored) {}

            if (byProfession == null) {
                // Fallback: iterate entries to find matching key
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

            if (byProfession == null) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: VillagerTrades.TRADES has no entry for prof={}", prof);
                return;
            }

            for (int lvl = 1; lvl <= level; lvl++) {
                VillagerTrades.ItemListing[] listings = getListingsForLevel(byProfession, lvl);
                if (listings == null || listings.length == 0) continue;

                EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: prof={} lvl={} listings={}", prof, lvl, listings.length);

                for (VillagerTrades.ItemListing listing : listings) {
                    if (listing == null) continue;

                    int samples = MAX_SAMPLE_PER_LISTING;
                    RandomSource rand = RandomSource.create(0xC0FFEE ^ listing.hashCode() ^ (lvl * 31));

                    for (int i = 0; i < samples; i++) {
                        MerchantOffer offer = safeGetOffer(listing, vill, rand);
                        if (offer == null) continue;

                        ItemStack res = offer.getResult();
                        if (res == null || res.isEmpty()) continue;

                        unique.putIfAbsent(keyOf(res), res.copy());

                        if (unique.size() >= MAX_TOTAL_ITEMS) {
                            EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: reached MAX_TOTAL_ITEMS={} while sampling; stopping.", MAX_TOTAL_ITEMS);
                            return;
                        }

                        try {
                            rand.nextInt();
                        } catch (Throwable ignored) {}
                    }
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] CatalogBuilder.addTradesFromVillagerTrades failed", t);
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
            }

            if (byProfession instanceof Map<?, ?> map) {
                Object v = map.get(lvl);
                if (v instanceof VillagerTrades.ItemListing[] arr) return arr;
            }

            return null;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.getListingsForLevel failed (soft): {}", t.toString());
            return null;
        }
    }

    private static MerchantOffer safeGetOffer(VillagerTrades.ItemListing listing, Villager vill, RandomSource rand) {
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
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.safeGetOffer failed (soft): {}", t.toString());
            return null;
        }
    }

    private static void tryAddAllEnchantedBooksIfLibrarian(Villager vill, VillagerProfession prof, Map<String, ItemStack> unique) {
        try {
            if (vill == null || prof == null) return;
            if (prof != VillagerProfession.LIBRARIAN) return;

            Registry<Enchantment> reg;
            try {
                reg = vill.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: cannot access enchantment registry; skipping enchanted book expansion.");
                return;
            }

            // If we cannot determine tradeability in this environment, do NOT expand the entire registry.
            // This prevents junk/dev/loot-only enchants from showing up.
            if (!ensureTradeableMethodLookedUp()) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: cannot resolve enchantment tradeable-flag method; skipping librarian registry expansion to avoid illegal books.");
                return;
            }

            final int before = unique.size();

            final int[] scanned = new int[] {0};
            final int[] tradeable = new int[] {0};
            final int[] filteredNotTradeable = new int[] {0};
            final int[] attempted = new int[] {0};
            final int[] added = new int[] {0};
            final int[] skippedCapArr = new int[] {0};
            final int[] skippedErrArr = new int[] {0};

            reg.holders().forEach(holder -> {
                try {
                    scanned[0]++;

                    if (unique.size() >= MAX_TOTAL_ITEMS) {
                        skippedCapArr[0]++;
                        return;
                    }

                    Enchantment ench;
                    try {
                        ench = holder.value();
                    } catch (Throwable t) {
                        skippedErrArr[0]++;
                        return;
                    }

                    Boolean isTradeable = isTradeableSafe(ench);
                    if (isTradeable == null) {
                        // Should not happen if ensureTradeableMethodLookedUp() succeeded,
                        // but keep it safe. Fail-closed for registry expansion.
                        filteredNotTradeable[0]++;
                        return;
                    }

                    if (!isTradeable.booleanValue()) {
                        filteredNotTradeable[0]++;
                        return;
                    }
                    tradeable[0]++;

                    int max = 1;
                    try {
                        max = Math.max(1, ench.getMaxLevel());
                    } catch (Throwable ignoredMax) {}

                    if (max > MAX_ENCHANTABILITY_LEVEL_ENUM) {
                        try {
                            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: enchant {} maxLevel={} exceeds cap {}; enumerating 1..{} only.",
                                    safeHolderId(reg, holder), max, MAX_ENCHANTABILITY_LEVEL_ENUM, MAX_ENCHANTABILITY_LEVEL_ENUM);
                        } catch (Throwable ignoredLog) {}
                        max = MAX_ENCHANTABILITY_LEVEL_ENUM;
                    }

                    for (int lvl = 1; lvl <= max; lvl++) {
                        if (unique.size() >= MAX_TOTAL_ITEMS) {
                            skippedCapArr[0]++;
                            break;
                        }

                        attempted[0]++;

                        ItemStack book;
                        try {
                            book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, lvl));
                        } catch (Throwable t) {
                            skippedErrArr[0]++;
                            continue;
                        }

                        if (book == null || book.isEmpty()) {
                            skippedErrArr[0]++;
                            continue;
                        }

                        String k = keyOf(book);
                        if (unique.putIfAbsent(k, book) == null) {
                            added[0]++;
                        }
                    }
                } catch (Throwable t) {
                    skippedErrArr[0]++;
                }
            });

            EZVillagerReroll.LOG().info(
                    "[EZVR] CatalogBuilder: librarian enchanted-book expansion scannedEnchants={} tradeableEnchants={} filteredNotTradeable={} attemptedBooks={} addedBooks={} skippedCap={} skippedErr={} size {}->{}",
                    scanned[0], tradeable[0], filteredNotTradeable[0], attempted[0], added[0], skippedCapArr[0], skippedErrArr[0],
                    before, unique.size()
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.tryAddAllEnchantedBooksIfLibrarian failed (soft): {}", t.toString());
        }
    }

    /**
     * @return true if we found a usable "tradeable flag" method, false otherwise.
     */
    private static boolean ensureTradeableMethodLookedUp() {
        try {
            if (LOOKED_UP_TRADEABLE_METHOD) return CACHED_TRADEABLE_METHOD != null;

            LOOKED_UP_TRADEABLE_METHOD = true;

            // Try the most likely name first
            Method m = null;
            try {
                m = Enchantment.class.getMethod("isTradeable");
            } catch (Throwable ignored) {}

            // Fallback names (some mappings use different naming)
            if (m == null) {
                try {
                    m = Enchantment.class.getMethod("isTradeableInVillagerTrades");
                } catch (Throwable ignored) {}
            }
            if (m == null) {
                try {
                    m = Enchantment.class.getMethod("canBeTraded");
                } catch (Throwable ignored) {}
            }

            if (m != null) {
                m.setAccessible(true);
                CACHED_TRADEABLE_METHOD = m;
                EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder: resolved tradeable method for enchantments: {}", m.getName());
                return true;
            }

            if (!LOGGED_TRADEABLE_LOOKUP_FAILURE) {
                LOGGED_TRADEABLE_LOOKUP_FAILURE = true;
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: could not find a tradeable-flag method on Enchantment (tried isTradeable, isTradeableInVillagerTrades, canBeTraded).");
            }

            CACHED_TRADEABLE_METHOD = null;
            return false;

        } catch (Throwable t) {
            if (!LOGGED_TRADEABLE_LOOKUP_FAILURE) {
                LOGGED_TRADEABLE_LOOKUP_FAILURE = true;
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: tradeable method lookup failed: {}", t.toString());
            }
            CACHED_TRADEABLE_METHOD = null;
            return false;
        }
    }

    /**
     * Returns:
     * - Boolean.TRUE / Boolean.FALSE if we can determine tradeability
     * - null if the environment doesn't expose a usable method (fail-closed for registry expansion)
     */
    private static Boolean isTradeableSafe(Enchantment ench) {
        try {
            if (ench == null) return Boolean.FALSE;

            if (!ensureTradeableMethodLookedUp()) return null;

            Method m = CACHED_TRADEABLE_METHOD;
            if (m == null) return null;

            Object v = m.invoke(ench);
            if (v instanceof Boolean b) return b.booleanValue() ? Boolean.TRUE : Boolean.FALSE;

            // Unexpected return type: treat as unknown
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: tradeable method returned non-boolean for {} -> {}",
                    ench.getClass().getName(), v == null ? "null" : v.getClass().getName());
            return null;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder: tradeable check failed for {} (soft): {}",
                    ench == null ? "null" : ench.getClass().getName(), t.toString());
            return null;
        }
    }

    private static String safeHolderId(Registry<Enchantment> reg, Holder<Enchantment> holder) {
        try {
            if (holder == null) return "null";
            try {
                Enchantment e = holder.value();
                if (e != null && reg != null) {
                    Object key = reg.getKey(e);
                    if (key != null) return String.valueOf(key);
                }
            } catch (Throwable ignored) {}
            return String.valueOf(holder);
        } catch (Throwable t) {
            return "<?>";
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
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.keyOf failed (soft): {}", t.toString());
            return "err|" + Objects.hashCode(s);
        }
    }
}
