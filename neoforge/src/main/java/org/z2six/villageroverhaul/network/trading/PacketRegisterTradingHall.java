package org.z2six.villageroverhaul.network.trading;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRegisterTradingHall(int villagerEntityId, long posAsLong) implements CustomPacketPayload {

    public static final Type<PacketRegisterTradingHall> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "register_trading_hall"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRegisterTradingHall> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRegisterTradingHall decode(RegistryFriendlyByteBuf buf) {
            return new PacketRegisterTradingHall(buf.readVarInt(), buf.readLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketRegisterTradingHall msg) {
            buf.writeVarInt(msg.villagerEntityId());
            buf.writeLong(msg.posAsLong());
        }
    };

    public static PacketRegisterTradingHall of(int villagerEntityId, BlockPos pos) {
        return new PacketRegisterTradingHall(villagerEntityId, pos == null ? 0L : pos.asLong());
    }

    public BlockPos pos() {
        try {
            return BlockPos.of(posAsLong);
        } catch (Throwable ignored) {
            return BlockPos.ZERO;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
