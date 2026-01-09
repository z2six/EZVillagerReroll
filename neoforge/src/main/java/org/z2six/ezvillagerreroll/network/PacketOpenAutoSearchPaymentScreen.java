// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketOpenAutoSearchPaymentScreen.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server -> Client: open the "auto-search payment" screen (settlement pending).
 *
 * Client should show:
 * - hourlyCost
 * - elapsedSeconds
 * - finalCost
 * - totalVillagerXp
 * - row of result items if paid (derived from offersIfPay)
 * - row of result items if declined (derived from offersIfDecline)
 * - highlight locked indices (lockMaskBefore) in green on BOTH rows
 * - highlight requested-found indices in yellow on PAY row (requestedTargets)
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
        int totalVillagerXp
) implements CustomPacketPayload {

    public static final Type<PacketOpenAutoSearchPaymentScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_auto_search_payment"));

    private static final String TAG_PAY = "pay";
    private static final String TAG_DECLINE = "decline";

    /**
     * Client-only cache to avoid requiring client handler signature changes.
     * - decode() stores totalVillagerXp keyed by villagerEntityId
     * - AutoSearchPaymentScreen can read it during construction
     *
     * Safe to exist on dedicated server too; it's just an in-memory map.
     */
    private static final Map<Integer, Integer> CLIENT_TOTAL_XP_CACHE = new ConcurrentHashMap<>();

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
                    // soft: leave lists empty
                    EZVillagerReroll.LOG().debug("[EZVR] PacketOpenAutoSearchPaymentScreen.decode: offers NBT read failed (soft): {}", t.toString());
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
                    // soft: keep empty
                    EZVillagerReroll.LOG().debug("[EZVR] PacketOpenAutoSearchPaymentScreen.decode: requestedTargets read failed (soft): {}", t.toString());
                }

                // NEW (backwards-friendly): totalVillagerXp appended at end
                int totalXp = -1;
                try {
                    totalXp = Math.max(0, buf.readVarInt());
                } catch (Throwable ignored) {
                    totalXp = -1;
                }

                if (totalXp >= 0) {
                    cacheClientTotalVillagerXp(id, totalXp);
                }

                return new PacketOpenAutoSearchPaymentScreen(id, hourly, fin, ticks, pay, decline, lockMask, requested, totalXp);

            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen decode failed", t);
                return new PacketOpenAutoSearchPaymentScreen(-1, 0, 0, 0, new ListTag(), new ListTag(), 0L, List.of(), -1);
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
                    // best effort: still write something
                    EZVillagerReroll.LOG().debug("[EZVR] PacketOpenAutoSearchPaymentScreen.encode: offers NBT write failed (soft): {}", t.toString());
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

                // NEW: append totalVillagerXp (so older decoders can still read the old prefix safely)
                try {
                    buf.writeVarInt(Math.max(-1, msg.totalVillagerXp()));
                } catch (Throwable t) {
                    buf.writeVarInt(-1);
                }

            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
