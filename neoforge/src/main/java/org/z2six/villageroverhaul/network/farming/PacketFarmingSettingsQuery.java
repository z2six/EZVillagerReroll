package org.z2six.villageroverhaul.network.farming;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingSettingsQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketFarmingSettingsQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_settings_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingSettingsQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketFarmingSettingsQuery::villagerEntityId,
                    PacketFarmingSettingsQuery::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

