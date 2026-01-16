// neoforge\src\main\java\org\z2six\villageroverhaul\network\recruit\PacketRecruitGateData.java
package org.z2six.villageroverhaul.network.recruit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRecruitGateData(
        int villagerEntityId,
        boolean ok,
        boolean recruited,
        boolean canUseControls
) implements CustomPacketPayload {

    public static final Type<PacketRecruitGateData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruit_gate_data"));

    public static final StreamCodec<FriendlyByteBuf, PacketRecruitGateData> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRecruitGateData decode(FriendlyByteBuf buf) {
            int id = 0;
            boolean ok = false;
            boolean rec = false;
            boolean can = false;

            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            try { ok = buf.readBoolean(); } catch (Throwable ignored) {}
            try { rec = buf.readBoolean(); } catch (Throwable ignored) {}
            try { can = buf.readBoolean(); } catch (Throwable ignored) {}

            return new PacketRecruitGateData(id, ok, rec, can);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRecruitGateData p) {
            buf.writeVarInt(p.villagerEntityId());
            buf.writeBoolean(p.ok());
            buf.writeBoolean(p.recruited());
            buf.writeBoolean(p.canUseControls());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
