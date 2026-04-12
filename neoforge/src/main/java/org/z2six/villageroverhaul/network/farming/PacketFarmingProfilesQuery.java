package org.z2six.villageroverhaul.network.farming;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingProfilesQuery() implements CustomPacketPayload {
    public static final Type<PacketFarmingProfilesQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_profiles_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingProfilesQuery> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> {}, buf -> new PacketFarmingProfilesQuery());

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
