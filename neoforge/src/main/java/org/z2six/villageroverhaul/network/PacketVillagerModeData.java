package org.z2six.villageroverhaul.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerModeData(int villagerEntityId, String modeId) implements CustomPacketPayload {

    public static final Type<PacketVillagerModeData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_mode_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerModeData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerModeData::villagerEntityId,
                    ByteBufCodecs.STRING_UTF8, PacketVillagerModeData::modeId,
                    PacketVillagerModeData::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
