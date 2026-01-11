package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRecruitCostQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketRecruitCostQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruit_cost_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketRecruitCostQuery> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRecruitCostQuery decode(FriendlyByteBuf buf) {
            int id = 0;
            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            return new PacketRecruitCostQuery(id);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRecruitCostQuery p) {
            buf.writeVarInt(p.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
