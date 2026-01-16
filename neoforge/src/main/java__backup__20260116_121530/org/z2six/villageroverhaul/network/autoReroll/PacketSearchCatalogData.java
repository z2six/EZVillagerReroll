// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketSearchCatalogData.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.ArrayList;
import java.util.List;

public record PacketSearchCatalogData(
        int villagerEntityId,
        List<ItemStack> catalog,
        int offerCount,
        int lockedCount,
        int effectivePaidOffers,
        int manualCost,
        int hourlyCost
) implements CustomPacketPayload {

    public static final Type<PacketSearchCatalogData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "search_catalog_data"));

    /**
     * Keep this in sync with server-side MAX_TOTAL_ITEMS conceptually.
     * This is the transport safety cap.
     */
    private static final int MAX_ITEMS_ON_WIRE = 16384;

    /**
     * Backwards-friendly alias for call-sites that used "items()".
     */
    public List<ItemStack> items() {
        return catalog();
    }

    public static PacketSearchCatalogData minimal(int villagerEntityId, List<ItemStack> catalog) {
        return new PacketSearchCatalogData(villagerEntityId, catalog, -1, -1, -1, -1, -1);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketSearchCatalogData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSearchCatalogData decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > MAX_ITEMS_ON_WIRE) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] PacketSearchCatalogData.decode: item count {} exceeds cap {}; truncating.",
                            n, MAX_ITEMS_ON_WIRE);
                    n = MAX_ITEMS_ON_WIRE;
                }

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }

                // Extended fields (newer protocol). If an older server sent only items, reads will fail.
                int offerCount = -1;
                int lockedCount = -1;
                int effectivePaidOffers = -1;
                int manualCost = -1;
                int hourlyCost = -1;

                try { offerCount = buf.readVarInt(); } catch (Throwable ignored) {}
                try { lockedCount = buf.readVarInt(); } catch (Throwable ignored) {}
                try { effectivePaidOffers = buf.readVarInt(); } catch (Throwable ignored) {}
                try { manualCost = buf.readVarInt(); } catch (Throwable ignored) {}
                try { hourlyCost = buf.readVarInt(); } catch (Throwable ignored) {}

                return new PacketSearchCatalogData(id, list, offerCount, lockedCount, effectivePaidOffers, manualCost, hourlyCost);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketSearchCatalogData decode failed", t);
                return new PacketSearchCatalogData(-1, List.of(), -1, -1, -1, -1, -1);
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketSearchCatalogData msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                List<ItemStack> list = msg.catalog() == null ? List.of() : msg.catalog();
                int raw = list.size();
                int n = Math.min(MAX_ITEMS_ON_WIRE, raw);
                buf.writeVarInt(n);

                if (raw > n) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] PacketSearchCatalogData.encode: truncating catalog {} -> {} (cap).", raw, n);
                }

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }

                // Extended fields (always written by current versions)
                buf.writeVarInt(Math.max(-1, msg.offerCount()));
                buf.writeVarInt(Math.max(-1, msg.lockedCount()));
                buf.writeVarInt(Math.max(-1, msg.effectivePaidOffers()));
                buf.writeVarInt(Math.max(-1, msg.manualCost()));
                buf.writeVarInt(Math.max(-1, msg.hourlyCost()));

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketSearchCatalogData encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
