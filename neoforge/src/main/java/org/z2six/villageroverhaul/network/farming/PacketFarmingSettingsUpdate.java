package org.z2six.villageroverhaul.network.farming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingSettingsUpdate(int villagerEntityId, CompoundTag settings) implements CustomPacketPayload {

    public static final Type<PacketFarmingSettingsUpdate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_settings_u"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingSettingsUpdate> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketFarmingSettingsUpdate::villagerEntityId,
                    ByteBufCodecs.COMPOUND_TAG, PacketFarmingSettingsUpdate::settings,
                    PacketFarmingSettingsUpdate::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

