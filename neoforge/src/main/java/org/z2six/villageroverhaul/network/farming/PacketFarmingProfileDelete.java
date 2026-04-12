package org.z2six.villageroverhaul.network.farming;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingProfileDelete(String name) implements CustomPacketPayload {
    private static final int MAX_NAME_LEN = 48;

    public static final Type<PacketFarmingProfileDelete> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_profiles_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingProfileDelete> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeUtf(msg.name() == null ? "" : msg.name(), MAX_NAME_LEN),
                    buf -> new PacketFarmingProfileDelete(buf.readUtf(MAX_NAME_LEN))
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
