// neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketRecruitVillager.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRecruitVillager(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketRecruitVillager> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruit_villager"));

    public static final StreamCodec<FriendlyByteBuf, PacketRecruitVillager> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRecruitVillager decode(FriendlyByteBuf buf) {
            int id = 0;
            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            return new PacketRecruitVillager(id);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRecruitVillager p) {
            buf.writeVarInt(p.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
