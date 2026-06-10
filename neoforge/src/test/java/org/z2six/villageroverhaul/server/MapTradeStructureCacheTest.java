package org.z2six.villageroverhaul.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class MapTradeStructureCacheTest {

    @Test
    void nearbyBlockOriginsShareCacheBucket() {
        assertEquals(MapTradeStructureCache.cacheOriginBucket(0), MapTradeStructureCache.cacheOriginBucket(15));
        assertNotEquals(MapTradeStructureCache.cacheOriginBucket(15), MapTradeStructureCache.cacheOriginBucket(16));
    }

    @Test
    void negativeBlockOriginsUseFloorDivisionBuckets() {
        assertEquals(-1, MapTradeStructureCache.cacheOriginBucket(-1));
        assertEquals(-1, MapTradeStructureCache.cacheOriginBucket(-16));
        assertEquals(-2, MapTradeStructureCache.cacheOriginBucket(-17));
    }
}
