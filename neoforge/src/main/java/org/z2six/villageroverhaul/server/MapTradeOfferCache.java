package org.z2six.villageroverhaul.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Caches generated cartographer map offer results.
 *
 * Vanilla creates and biome-renders a fresh filled map each time the offer is generated.
 * Rerolling can call that path several times per click, so repeated cartographer rerolls
 * otherwise keep doing expensive map work for equivalent origin/destination pairs.
 */
public final class MapTradeOfferCache {

    private static final int MAP_SEARCH_RADIUS = 100;
    private static final boolean SKIP_KNOWN_STRUCTURES = true;
    private static final int MAX_ENTRIES = 2048;
    private static final long TTL_TICKS = 20L * 60L * 10L;

    private static final Map<CacheKey, CacheEntry> CACHE = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private MapTradeOfferCache() {}

    public static MerchantOffer createTreasureMapOffer(
            Entity trader,
            int emeraldCost,
            TagKey<Structure> destination,
            String displayName,
            Holder<MapDecorationType> destinationType,
            int maxUses,
            int villagerXp
    ) {
        try {
            if (trader == null || destination == null || displayName == null || destinationType == null) return null;
            if (!(trader.level() instanceof ServerLevel level)) return null;

            BlockPos origin = trader.blockPosition();
            if (origin == null) return null;

            long now = safeGameTime(level);
            CacheKey key = new CacheKey(
                    System.identityHashCode(level.getServer()),
                    String.valueOf(level.dimension().location()),
                    safeTagId(destination),
                    MapTradeStructureCache.cacheOriginBucket(origin.getX()),
                    MapTradeStructureCache.cacheOriginBucket(origin.getZ()),
                    displayName,
                    safeDecorationTypeId(destinationType)
            );

            synchronized (CACHE) {
                CacheEntry cached = CACHE.get(key);
                if (cached != null) {
                    if (now < 0L || (now - cached.cachedAtTick) <= TTL_TICKS) {
                        return offerFromCached(cached.result, emeraldCost, maxUses, villagerXp);
                    }
                    CACHE.remove(key);
                }
            }

            long startedAt = System.nanoTime();
            BlockPos found = MapTradeStructureCache.findNearestMapStructureCached(
                    level,
                    destination,
                    origin,
                    MAP_SEARCH_RADIUS,
                    SKIP_KNOWN_STRUCTURES
            );

            ItemStack result = ItemStack.EMPTY;
            if (found != null) {
                result = MapItem.create(level, found.getX(), found.getZ(), (byte) 2, true, true);
                // Intentionally skip MapItem.renderBiomePreviewMap here. It is expensive and the target
                // decoration plus map center are enough for the explorer map to function.
                MapItemSavedData.addTargetDecoration(result, found, "+", destinationType);
                result.set(DataComponents.ITEM_NAME, Component.translatable(displayName));
            }

            ItemStack stored = result == null || result.isEmpty() ? ItemStack.EMPTY : result.copy();
            synchronized (CACHE) {
                CACHE.put(key, new CacheEntry(stored, now));
            }

            long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (tookMs >= 100L) {
                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] Cached cartographer map offer: dim={} tag={} bucket=({}, {}) result={} took={}ms",
                        key.dimensionId,
                        key.destinationTagId,
                        key.originBucketX,
                        key.originBucketZ,
                        found == null ? "null" : found.toShortString(),
                        tookMs
                );
            }

            return offerFromCached(stored, emeraldCost, maxUses, villagerXp);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] MapTradeOfferCache failed: {}", t.toString());
            return null;
        }
    }

    private static MerchantOffer offerFromCached(ItemStack cachedResult, int emeraldCost, int maxUses, int villagerXp) {
        try {
            if (cachedResult == null || cachedResult.isEmpty()) return null;
            return new MerchantOffer(
                    new ItemCost(Items.EMERALD, emeraldCost),
                    Optional.of(new ItemCost(Items.COMPASS)),
                    cachedResult.copy(),
                    maxUses,
                    villagerXp,
                    0.2F
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static long safeGameTime(ServerLevel level) {
        try {
            return level.getGameTime();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String safeTagId(TagKey<Structure> destination) {
        try {
            ResourceLocation id = destination.location();
            return id == null ? String.valueOf(destination) : id.toString();
        } catch (Throwable ignored) {
            return String.valueOf(destination);
        }
    }

    private static String safeDecorationTypeId(Holder<MapDecorationType> destinationType) {
        try {
            return destinationType.unwrapKey()
                    .map(key -> String.valueOf(key.location()))
                    .orElseGet(() -> String.valueOf(destinationType.value()));
        } catch (Throwable ignored) {
            return String.valueOf(destinationType);
        }
    }

    private record CacheKey(
            int serverIdentity,
            String dimensionId,
            String destinationTagId,
            int originBucketX,
            int originBucketZ,
            String displayName,
            String destinationTypeId
    ) {}

    private record CacheEntry(ItemStack result, long cachedAtTick) {}
}
