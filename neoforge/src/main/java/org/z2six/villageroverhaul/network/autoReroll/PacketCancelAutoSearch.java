// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketCancelAutoSearch.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketCancelAutoSearch(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCancelAutoSearch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cancel_auto_search"));

    public static final StreamCodec<FriendlyByteBuf, PacketCancelAutoSearch> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketCancelAutoSearch decode(FriendlyByteBuf buf) {
            return new PacketCancelAutoSearch(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketCancelAutoSearch msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
