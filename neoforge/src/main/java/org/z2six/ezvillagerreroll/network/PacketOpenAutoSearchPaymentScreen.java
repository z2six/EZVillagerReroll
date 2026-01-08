// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketOpenAutoSearchPaymentScreen.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server -> Client: open the "auto-search payment" screen (settlement pending).
 *
 * Client should show:
 * - hourlyCost
 * - elapsedSeconds
 * - finalCost
 * - snapshot of offers if PAY (auto-search result)
 * - snapshot of offers if DECLINE (pre-search baseline)
 * - lockMask for DECLINE snapshot (green outline for locked trades)
 * - requested item ids for PAY highlight (yellow outline if result matches any requested target)
 *
 * Buttons:
 * - Pay -> send PacketPayAutoSearchSettlement(villagerEntityId)
 * - Decline -> send PacketDeclineAutoSearchSettlement(villagerEntityId)
 *
 * Encoding notes:
 * We keep the first 4 fields as varints, then a single NBT compound holding the rest:
 * { pay:List, decline:List, lockMask:Long, requested:List<String> }
 */
public record PacketOpenAutoSearchPaymentScreen(
        int villagerEntityId,
        int hourlyCost,
        int finalCost,
        int elapsedTicks,
        ListTag offersIfPay,
        ListTag offersIfDecline,
        long declineLockMask,
        List<String> requestedItemIds
) implements CustomPacketPayload {

    public static final Type<PacketOpenAutoSearchPaymentScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_auto_search_payment"));

    private static final String TAG_ROOT_PAY = "pay";
    private static final String TAG_ROOT_DECLINE = "decline";
    private static final String TAG_ROOT_LOCK_MASK = "lockMask";
    private static final String TAG_ROOT_REQUESTED = "requested";

    private static final int MAX_OFFERS = 256;
    private static final int MAX_REQUESTED = 128;

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenAutoSearchPaymentScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenAutoSearchPaymentScreen decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int hourly = buf.readVarInt();
                int fin = buf.readVarInt();
                int ticks = buf.readVarInt();

                CompoundTag root = null;
                try {
                    root = buf.readNbt();
                } catch (Throwable t) {
                    root = null;
                }

                ListTag pay = new ListTag();
                ListTag decline = new ListTag();
                long lockMask = 0L;
                List<String> requested = Collections.emptyList();

                if (root != null) {
                    try {
                        if (root.contains(TAG_ROOT_PAY, Tag.TAG_LIST)) {
                            pay = root.getList(TAG_ROOT_PAY, Tag.TAG_COMPOUND);
                        }
                    } catch (Throwable ignored) {}

                    try {
                        if (root.contains(TAG_ROOT_DECLINE, Tag.TAG_LIST)) {
                            decline = root.getList(TAG_ROOT_DECLINE, Tag.TAG_COMPOUND);
                        }
                    } catch (Throwable ignored) {}

                    try {
                        if (root.contains(TAG_ROOT_LOCK_MASK, Tag.TAG_LONG)) {
                            lockMask = root.getLong(TAG_ROOT_LOCK_MASK);
                        }
                    } catch (Throwable ignored) {}

                    try {
                        if (root.contains(TAG_ROOT_REQUESTED, Tag.TAG_LIST)) {
                            ListTag req = root.getList(TAG_ROOT_REQUESTED, Tag.TAG_STRING);
                            ArrayList<String> out = new ArrayList<>();
                            int n = Math.min(MAX_REQUESTED, req.size());
                            for (int i = 0; i < n; i++) {
                                try {
                                    String s = req.getString(i);
                                    if (s != null && !s.isBlank()) out.add(s);
                                } catch (Throwable ignored) {}
                            }
                            requested = out;
                        }
                    } catch (Throwable ignored) {}
                }

                ListTag payCopy = deepCopyOffersList(pay);
                ListTag declineCopy = deepCopyOffersList(decline);

                List<String> requestedCopy;
                try {
                    if (requested == null || requested.isEmpty()) requestedCopy = Collections.emptyList();
                    else requestedCopy = List.copyOf(requested);
                } catch (Throwable t) {
                    requestedCopy = Collections.emptyList();
                }

                EZVillagerReroll.LOG().debug("[EZVR] PacketOpenAutoSearchPaymentScreen decoded: villagerEntityId={} hourly={} final={} ticks={} payOffers={} declineOffers={} lockMask={} requested={}",
                        id, hourly, fin, ticks,
                        payCopy.size(), declineCopy.size(),
                        Long.toUnsignedString(lockMask),
                        requestedCopy.size()
                );

                return new PacketOpenAutoSearchPaymentScreen(
                        id,
                        Math.max(0, hourly),
                        Math.max(0, fin),
                        Math.max(0, ticks),
                        payCopy,
                        declineCopy,
                        lockMask,
                        requestedCopy
                );
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen decode failed", t);
                return new PacketOpenAutoSearchPaymentScreen(-1, 0, 0, 0, new ListTag(), new ListTag(), 0L, List.of());
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketOpenAutoSearchPaymentScreen msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                buf.writeVarInt(Math.max(0, msg.hourlyCost()));
                buf.writeVarInt(Math.max(0, msg.finalCost()));
                buf.writeVarInt(Math.max(0, msg.elapsedTicks()));

                CompoundTag root = new CompoundTag();

                ListTag pay = msg.offersIfPay() == null ? new ListTag() : msg.offersIfPay();
                ListTag decline = msg.offersIfDecline() == null ? new ListTag() : msg.offersIfDecline();

                ListTag payCopy = deepCopyOffersList(pay);
                ListTag declineCopy = deepCopyOffersList(decline);

                root.put(TAG_ROOT_PAY, payCopy);
                root.put(TAG_ROOT_DECLINE, declineCopy);

                root.putLong(TAG_ROOT_LOCK_MASK, msg.declineLockMask());

                ListTag req = new ListTag();
                try {
                    List<String> ids = msg.requestedItemIds();
                    if (ids != null) {
                        int n = Math.min(MAX_REQUESTED, ids.size());
                        for (int i = 0; i < n; i++) {
                            String s = ids.get(i);
                            if (s == null || s.isBlank()) continue;
                            req.add(StringTag.valueOf(s));
                        }
                    }
                } catch (Throwable ignored) {}
                root.put(TAG_ROOT_REQUESTED, req);

                buf.writeNbt(root);

                EZVillagerReroll.LOG().debug("[EZVR] PacketOpenAutoSearchPaymentScreen encoded: villagerEntityId={} payOffers={} declineOffers={} lockMask={} requested={}",
                        msg.villagerEntityId(),
                        payCopy.size(),
                        declineCopy.size(),
                        Long.toUnsignedString(msg.declineLockMask()),
                        msg.requestedItemIds() == null ? -1 : msg.requestedItemIds().size()
                );

            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen encode failed", t);
            }
        }

        private static ListTag deepCopyOffersList(ListTag src) {
            try {
                ListTag out = new ListTag();
                if (src == null) return out;

                int n = Math.min(MAX_OFFERS, src.size());
                for (int i = 0; i < n; i++) {
                    try {
                        CompoundTag wrap = src.getCompound(i);
                        if (wrap != null) out.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
                return out;
            } catch (Throwable t) {
                return new ListTag();
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
