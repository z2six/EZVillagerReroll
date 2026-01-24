// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketOpenAutoSearchPaymentScreen.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server -> Client: open the "auto-search payment" screen (settlement pending).
 */
public record PacketOpenAutoSearchPaymentScreen(
        int villagerEntityId,
        int hourlyCost,
        int finalCost,
        int elapsedTicks,
        ListTag offersIfPay,
        ListTag offersIfDecline,
        long lockMaskBefore,
        List<String> requestedTargets,
        int totalVillagerXp,
        int rerollCount,
        Map<String, Long> tooltipValueVByKey
) implements CustomPacketPayload {

    public static final Type<PacketOpenAutoSearchPaymentScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_auto_search_payment"));

    private static final String TAG_PAY = "pay";
    private static final String TAG_DECLINE = "decline";

    /**
     * Client-only cache to avoid requiring client handler signature changes.
     * - decode() stores totalVillagerXp keyed by villagerEntityId
     * - AutoSearchPaymentScreen can read it during construction
     */
    private static final Map<Integer, Integer> CLIENT_TOTAL_XP_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Map<String, Long>> CLIENT_TOOLTIP_V_CACHE = new ConcurrentHashMap<>();

    public static void cacheClientTotalVillagerXp(int villagerEntityId, int xp) {
        try {
            if (villagerEntityId < 0) return;
            if (xp < 0) xp = 0;
            CLIENT_TOTAL_XP_CACHE.put(villagerEntityId, xp);
        } catch (Throwable ignored) {}
    }

    public static int popClientTotalVillagerXp(int villagerEntityId) {
        try {
            if (villagerEntityId < 0) return -1;
            Integer v = CLIENT_TOTAL_XP_CACHE.remove(villagerEntityId);
            return v == null ? -1 : v;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void cacheClientTooltipVByKey(int villagerEntityId, Map<String, Long> map) {
        try {
            if (villagerEntityId < 0) return;
            if (map == null || map.isEmpty()) return;
            CLIENT_TOOLTIP_V_CACHE.put(villagerEntityId, map);
        } catch (Throwable ignored) {}
    }

    public static Map<String, Long> popClientTooltipVByKey(int villagerEntityId) {
        try {
            if (villagerEntityId < 0) return Map.of();
            Map<String, Long> v = CLIENT_TOOLTIP_V_CACHE.remove(villagerEntityId);
            return v == null ? Map.of() : v;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenAutoSearchPaymentScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenAutoSearchPaymentScreen decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int hourly = Math.max(0, buf.readVarInt());
                int fin = Math.max(0, buf.readVarInt());
                int ticks = Math.max(0, buf.readVarInt());

                ListTag pay = new ListTag();
                ListTag decline = new ListTag();

                long lockMask = 0L;

                try {
                    CompoundTag root = buf.readNbt();
                    if (root != null) {
                        if (root.contains(TAG_PAY, Tag.TAG_LIST)) {
                            pay = root.getList(TAG_PAY, Tag.TAG_COMPOUND);
                        }
                        if (root.contains(TAG_DECLINE, Tag.TAG_LIST)) {
                            decline = root.getList(TAG_DECLINE, Tag.TAG_COMPOUND);
                        }
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] PacketOpenAutoSearchPaymentScreen.decode: offers NBT read failed (soft): {}", t.toString());
                }

                try {
                    lockMask = buf.readLong();
                } catch (Throwable t) {
                    lockMask = 0L;
                }

                List<String> requested = new ArrayList<>();
                try {
                    int n = buf.readVarInt();
                    if (n < 0) n = 0;
                    if (n > 256) n = 256;

                    for (int i = 0; i < n; i++) {
                        String s = buf.readUtf(32767);
                        if (s == null) continue;
                        s = s.trim();
                        if (!s.isEmpty()) requested.add(s);
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] PacketOpenAutoSearchPaymentScreen.decode: requestedTargets read failed (soft): {}", t.toString());
                }

                // Appended fields (backwards-friendly):
                int totalXp = -1;
                try {
                    totalXp = Math.max(0, buf.readVarInt());
                } catch (Throwable ignored) {
                    totalXp = -1;
                }
                if (totalXp >= 0) {
                    cacheClientTotalVillagerXp(id, totalXp);
                }

                int rr = -1;
                try {
                    rr = Math.max(0, buf.readVarInt());
                } catch (Throwable ignored) {
                    rr = -1;
                }
                if (rr >= 0) {
                    cacheClientRerollCount(id, rr);
                } else {
                    // if missing (old server), treat as 0 but don't poison cache
                    rr = 0;
                }

                Map<String, Long> tooltipV = Map.of();
                try {
                    int vn = buf.readVarInt();
                    if (vn < 0) vn = 0;
                    if (vn > 2048) vn = 2048;
                    LinkedHashMap<String, Long> tmp = new LinkedHashMap<>();
                    for (int i = 0; i < vn; i++) {
                        String k = buf.readUtf(32767);
                        long v = Math.max(0L, buf.readLong());
                        if (k == null) continue;
                        k = k.trim();
                        if (k.isEmpty()) continue;
                        if (v <= 0L) continue;
                        tmp.put(k, v);
                    }
                    tooltipV = tmp;
                } catch (Throwable ignored) {}
                if (tooltipV != null && !tooltipV.isEmpty()) {
                    cacheClientTooltipVByKey(id, tooltipV);
                }

                return new PacketOpenAutoSearchPaymentScreen(id, hourly, fin, ticks, pay, decline, lockMask, requested, totalXp, rr, tooltipV);

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketOpenAutoSearchPaymentScreen decode failed", t);
                return new PacketOpenAutoSearchPaymentScreen(-1, 0, 0, 0, new ListTag(), new ListTag(), 0L, List.of(), -1, 0, Map.of());
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketOpenAutoSearchPaymentScreen msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                buf.writeVarInt(Math.max(0, msg.hourlyCost()));
                buf.writeVarInt(Math.max(0, msg.finalCost()));
                buf.writeVarInt(Math.max(0, msg.elapsedTicks()));

                try {
                    CompoundTag root = new CompoundTag();
                    root.put(TAG_PAY, msg.offersIfPay() == null ? new ListTag() : msg.offersIfPay());
                    root.put(TAG_DECLINE, msg.offersIfDecline() == null ? new ListTag() : msg.offersIfDecline());
                    buf.writeNbt(root);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] PacketOpenAutoSearchPaymentScreen.encode: offers NBT write failed (soft): {}", t.toString());
                    buf.writeNbt(new CompoundTag());
                }

                try {
                    buf.writeLong(msg.lockMaskBefore());
                } catch (Throwable t) {
                    buf.writeLong(0L);
                }

                List<String> req = msg.requestedTargets();
                int n = req == null ? 0 : Math.min(256, req.size());
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    String s = req.get(i);
                    if (s == null) s = "";
                    if (s.length() > 32767) s = s.substring(0, 32767);
                    buf.writeUtf(s);
                }

                // Appended fields (so old decoders can still read the prefix safely)
                try {
                    buf.writeVarInt(Math.max(-1, msg.totalVillagerXp()));
                } catch (Throwable t) {
                    buf.writeVarInt(-1);
                }

                try {
                    buf.writeVarInt(Math.max(0, msg.rerollCount()));
                } catch (Throwable t) {
                    buf.writeVarInt(0);
                }

                // Appended fields (so old decoders can still read the prefix safely)
                try {
                    Map<String, Long> map = msg.tooltipValueVByKey() == null ? Map.of() : msg.tooltipValueVByKey();
                    int vn = Math.min(2048, map.size());
                    buf.writeVarInt(vn);
                    int i = 0;
                    for (Map.Entry<String, Long> en : map.entrySet()) {
                        if (i >= vn) break;
                        String k = en.getKey();
                        long v = en.getValue() == null ? 0L : Math.max(0L, en.getValue());
                        if (k == null) k = "";
                        if (k.length() > 32767) k = k.substring(0, 32767);
                        buf.writeUtf(k);
                        buf.writeLong(v);
                        i++;
                    }
                } catch (Throwable t) {
                    buf.writeVarInt(0);
                }

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketOpenAutoSearchPaymentScreen encode failed", t);
            }
        }
    };

    /**
     * Client-only cache to avoid requiring client handler signature changes.
     * - decode() stores rerollCount keyed by villagerEntityId
     * - AutoSearchPaymentScreen can read it during construction
     */
    private static final Map<Integer, Integer> CLIENT_REROLL_COUNT_CACHE = new ConcurrentHashMap<>();

    public static void cacheClientRerollCount(int villagerEntityId, int rerolls) {
        try {
            if (villagerEntityId < 0) return;
            if (rerolls < 0) rerolls = 0;
            CLIENT_REROLL_COUNT_CACHE.put(villagerEntityId, rerolls);
        } catch (Throwable ignored) {}
    }

    public static int popClientRerollCount(int villagerEntityId) {
        try {
            if (villagerEntityId < 0) return -1;
            Integer v = CLIENT_REROLL_COUNT_CACHE.remove(villagerEntityId);
            return v == null ? -1 : v;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
