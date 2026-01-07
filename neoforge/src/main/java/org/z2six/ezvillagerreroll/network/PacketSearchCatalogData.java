// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketSearchCatalogData.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.ArrayList;
import java.util.List;

public record PacketSearchCatalogData(int villagerEntityId, List<ItemStack> catalog) implements CustomPacketPayload {

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

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketSearchCatalogData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSearchCatalogData decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > MAX_ITEMS_ON_WIRE) {
                    EZVillagerReroll.LOG().warn("[EZVR] PacketSearchCatalogData.decode: item count {} exceeds cap {}; truncating.",
                            n, MAX_ITEMS_ON_WIRE);
                    n = MAX_ITEMS_ON_WIRE;
                }

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }
                return new PacketSearchCatalogData(id, list);
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketSearchCatalogData decode failed", t);
                return new PacketSearchCatalogData(-1, List.of());
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
                    EZVillagerReroll.LOG().warn("[EZVR] PacketSearchCatalogData.encode: truncating catalog {} -> {} (cap).", raw, n);
                }

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketSearchCatalogData encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
