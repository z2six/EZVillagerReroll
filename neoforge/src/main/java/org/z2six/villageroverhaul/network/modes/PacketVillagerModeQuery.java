package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerModeQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerModeQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_mode_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerModeQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerModeQuery::villagerEntityId,
                    PacketVillagerModeQuery::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
