// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketSearchCatalogQuery.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketSearchCatalogQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketSearchCatalogQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "search_catalog_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketSearchCatalogQuery> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketSearchCatalogQuery decode(FriendlyByteBuf buf) {
            return new PacketSearchCatalogQuery(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketSearchCatalogQuery msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
