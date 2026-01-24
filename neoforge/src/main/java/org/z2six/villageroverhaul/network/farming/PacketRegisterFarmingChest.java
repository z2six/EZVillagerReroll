package org.z2six.villageroverhaul.network.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRegisterFarmingChest(int villagerEntityId, long posAsLong) implements CustomPacketPayload {

    public BlockPos pos() {
        try { return BlockPos.of(posAsLong); } catch (Throwable ignored) { return BlockPos.ZERO; }
    }

    public static final Type<PacketRegisterFarmingChest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_chest_r"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRegisterFarmingChest> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRegisterFarmingChest decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            long pos = buf.readLong();
            return new PacketRegisterFarmingChest(id, pos);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketRegisterFarmingChest msg) {
            buf.writeVarInt(msg.villagerEntityId());
            buf.writeLong(msg.posAsLong());
        }
    };

    public static PacketRegisterFarmingChest of(int villagerEntityId, BlockPos pos) {
        long l = 0L;
        try { l = pos == null ? 0L : pos.asLong(); } catch (Throwable ignored) { l = 0L; }
        return new PacketRegisterFarmingChest(villagerEntityId, l);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
