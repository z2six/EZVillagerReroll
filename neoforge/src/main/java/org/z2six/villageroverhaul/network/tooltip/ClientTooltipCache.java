// neoforge\src\main\java\org\z2six\villageroverhaul\network\tooltip\ClientTooltipCache.java
package org.z2six.villageroverhaul.network.tooltip;

public final class ClientTooltipCache {

    private static volatile PacketTooltipData last;
    private static volatile long lastUpdateMs;

    public static void set(PacketTooltipData data) {
        last = data;
        lastUpdateMs = System.currentTimeMillis();
    }

    public static PacketTooltipData get() {
        return last;
    }

    public static long ageMs() {
        long t = lastUpdateMs;
        return t == 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - t);
    }

    private ClientTooltipCache() {}
}
