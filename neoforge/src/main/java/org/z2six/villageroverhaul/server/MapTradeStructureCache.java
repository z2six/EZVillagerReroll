package org.z2six.villageroverhaul.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caches cartographer structure lookups so repeated rerolls do not repeatedly
 * force synchronous nearest-structure scans on the server thread.
 */
public final class MapTradeStructureCache {

    private static final int MAX_ENTRIES = 2048;
    private static final long TTL_TICKS = 20L * 60L * 10L;

    private static final Map<CacheKey, CacheEntry> CACHE = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private MapTradeStructureCache() {}

    public static BlockPos findNearestMapStructureCached(ServerLevel level, TagKey<Structure> destination, BlockPos origin, int radius, boolean skipKnown) {
        try {
            if (level == null || destination == null || origin == null) {
                return null;
            }

            final long now = safeGameTime(level);
            final int originBucketX = cacheOriginBucket(origin.getX());
            final int originBucketZ = cacheOriginBucket(origin.getZ());
            final CacheKey key = new CacheKey(
                    System.identityHashCode(level.getServer()),
                    String.valueOf(level.dimension().location()),
                    safeTagId(destination),
                    originBucketX,
                    originBucketZ,
                    radius,
                    skipKnown
            );

            synchronized (CACHE) {
                CacheEntry cached = CACHE.get(key);
                if (cached != null) {
                    if (now < 0L || (now - cached.cachedAtTick) <= TTL_TICKS) {
                        return cached.pos == null ? null : cached.pos.immutable();
                    }
                    CACHE.remove(key);
                }
            }

            final long startedAt = System.nanoTime();
            BlockPos found = level.findNearestMapStructure(destination, origin, radius, skipKnown);
            final long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;
            final BlockPos stored = found == null ? null : found.immutable();

            synchronized (CACHE) {
                CACHE.put(key, new CacheEntry(stored, now));
            }

            if (tookMs >= 100L) {
                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] Cached cartographer structure lookup: dim={} tag={} pos=({}, {}) radius={} result={} took={}ms",
                        key.dimensionId,
                        key.destinationTagId,
                        key.originBucketX,
                        key.originBucketZ,
                        radius,
                        stored == null ? "null" : stored.toShortString(),
                        tookMs
                );
            }

            return stored;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] MapTradeStructureCache failed; falling back to vanilla: {}", t.toString());
            try {
                return level.findNearestMapStructure(destination, origin, radius, skipKnown);
            } catch (Throwable ignored) {
                return null;
            }
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

    static int cacheOriginBucket(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, 16);
    }

    private record CacheKey(
            int serverIdentity,
            String dimensionId,
            String destinationTagId,
            int originBucketX,
            int originBucketZ,
            int radius,
            boolean skipKnown
    ) {}

    private record CacheEntry(BlockPos pos, long cachedAtTick) {}
}
