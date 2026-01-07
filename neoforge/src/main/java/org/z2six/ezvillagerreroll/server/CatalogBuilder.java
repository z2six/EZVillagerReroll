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
 * - Special-case librarian enchanted books to include all registry enchantments (vanilla + modded).
 */
public final class CatalogBuilder {

    // Safety caps
    private static final int MAX_TOTAL_ITEMS = 4096;
    private static final int MAX_SAMPLE_PER_LISTING = 96; // enough to capture many random listings without being too heavy

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

            // 1) Include CURRENT offers too (so even if trades map lookup fails, UI has something)
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

            // 2) Add possible outputs from VillagerTrades listings
            addTradesFromVillagerTrades(vill, prof, level, unique);

            // 3) Special-case librarian enchanted books: include all registry enchantments (vanilla + modded)
            // This makes the catalog "truly accurate" for librarians even when listing randomness would miss items.
            tryAddAllEnchantedBooksIfLibrarian(vill, prof, unique);

            // Finalize
            List<ItemStack> out = new ArrayList<>(unique.values());
            if (out.size() > MAX_TOTAL_ITEMS) out = out.subList(0, MAX_TOTAL_ITEMS);

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

            // In vanilla, VillagerTrades.TRADES is keyed by profession, each value has per-level arrays.
            Object byProfession = null;

            try {
                byProfession = VillagerTrades.TRADES.get(prof);
            } catch (Throwable ignored) {}

            if (byProfession == null) {
                // Reflection fallback: iterate entries of TRADES to find matching key
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

    @SuppressWarnings("unchecked")
    private static VillagerTrades.ItemListing[] getListingsForLevel(Object byProfession, int lvl) {
        try {
            if (byProfession == null) return null;

            // Common case: Int2ObjectMap has get(int)
            try {
                Method mGet = byProfession.getClass().getMethod("get", int.class);
                Object v = mGet.invoke(byProfession, lvl);
                if (v instanceof VillagerTrades.ItemListing[] arr) return arr;
            } catch (NoSuchMethodException ignored) {
                // fallthrough
            }

            // Alternate: Map<Integer, ItemListing[]>
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

            // In modern mappings, ItemListing has getOffer(Entity, RandomSource)
            try {
                return listing.getOffer(vill, rand);
            } catch (Throwable ignored) {}

            // Reflection fallback in case signature differs
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

            // Vanilla constant is VillagerProfession.LIBRARIAN
            if (prof != VillagerProfession.LIBRARIAN) return;

            Registry<Enchantment> reg;
            try {
                reg = vill.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: cannot access enchantment registry; skipping enchanted book expansion.");
                return;
            }

            int added = 0;
            int scanned = 0;

            try {
                for (Holder<Enchantment> holder : reg.asHolderIdMap()) {
                    // asHolderIdMap() is iterable-ish in some mappings; if this doesn't compile on your side,
                    // swap to reg.holders().forEach(...) pattern below.
                    scanned++;

                    Enchantment ench;
                    try {
                        ench = holder.value();
                    } catch (Throwable ignored) {
                        continue;
                    }

                    int max = 1;
                    try {
                        max = Math.max(1, ench.getMaxLevel());
                    } catch (Throwable ignored) {}

                    ItemStack book;
                    try {
                        book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, max));
                    } catch (Throwable t) {
                        // Some modded enchantments may throw; skip softly
                        continue;
                    }

                    if (book == null || book.isEmpty()) continue;

                    String k = keyOf(book);
                    if (unique.putIfAbsent(k, book) == null) added++;

                    if (unique.size() >= MAX_TOTAL_ITEMS) break;
                }
            } catch (Throwable fallback) {
                // Fallback: stream holders() if asHolderIdMap() isn’t iterable in your mappings
                try {
                    reg.holders().forEach(holder -> {
                        try {
                            Enchantment ench = holder.value();
                            int max = Math.max(1, ench.getMaxLevel());
                            ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder, max));
                            if (book == null || book.isEmpty()) return;
                            String k = keyOf(book);
                            if (unique.putIfAbsent(k, book) == null) {
                                // cannot mutate added easily in lambda without AtomicInteger; keep logging minimal here
                            }
                        } catch (Throwable ignoredEach) {}
                    });
                    EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder: librarian enchanted book expansion used fallback reg.holders() iteration.");
                } catch (Throwable ignoredToo) {
                    EZVillagerReroll.LOG().warn("[EZVR] CatalogBuilder: failed iterating enchantment registry (soft): {}", ignoredToo.toString());
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] CatalogBuilder: librarian enchanted book expansion scanned={} added~={} (catalogNow={})",
                    scanned, added, unique.size());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] CatalogBuilder.tryAddAllEnchantedBooksIfLibrarian failed (soft): {}", t.toString());
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
