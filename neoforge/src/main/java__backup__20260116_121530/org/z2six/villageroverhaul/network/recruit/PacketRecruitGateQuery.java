// PacketRecruitGateQuery.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketRecruitGateQuery.java
package org.z2six.villageroverhaul.network.recruit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRecruitGateQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketRecruitGateQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruit_gate_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketRecruitGateQuery> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRecruitGateQuery decode(FriendlyByteBuf buf) {
            int id = 0;
            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            return new PacketRecruitGateQuery(id);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRecruitGateQuery p) {
            buf.writeVarInt(p.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
