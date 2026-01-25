package org.z2six.villageroverhaul.network.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client->server: register a manual farming workstation (any block) for a specific villager.
 */
public record PacketRegisterFarmingWorkstation(int villagerEntityId, long posAsLong) implements CustomPacketPayload {

    public static final Type<PacketRegisterFarmingWorkstation> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "register_farming_workstation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRegisterFarmingWorkstation> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRegisterFarmingWorkstation decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            long pos = buf.readLong();
            return new PacketRegisterFarmingWorkstation(id, pos);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketRegisterFarmingWorkstation msg) {
            buf.writeVarInt(msg.villagerEntityId());
            buf.writeLong(msg.posAsLong());
        }
    };

    public BlockPos pos() {
        return BlockPos.of(posAsLong);
    }

    public static PacketRegisterFarmingWorkstation of(int villagerEntityId, BlockPos pos) {
        long l = pos == null ? 0L : pos.asLong();
        return new PacketRegisterFarmingWorkstation(villagerEntityId, l);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

