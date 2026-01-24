package org.z2six.villageroverhaul.network.farming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingSettingsData(int villagerEntityId, CompoundTag settings) implements CustomPacketPayload {

    public static final Type<PacketFarmingSettingsData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_settings_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingSettingsData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketFarmingSettingsData::villagerEntityId,
                    ByteBufCodecs.COMPOUND_TAG, PacketFarmingSettingsData::settings,
                    PacketFarmingSettingsData::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

