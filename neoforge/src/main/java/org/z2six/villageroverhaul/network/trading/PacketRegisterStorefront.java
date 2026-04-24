package org.z2six.villageroverhaul.network.trading;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRegisterStorefront(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketRegisterStorefront> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "register_storefront"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRegisterStorefront> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRegisterStorefront decode(RegistryFriendlyByteBuf buf) {
            return new PacketRegisterStorefront(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketRegisterStorefront msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
