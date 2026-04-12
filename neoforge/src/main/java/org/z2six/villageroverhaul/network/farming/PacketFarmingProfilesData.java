package org.z2six.villageroverhaul.network.farming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingProfilesData(CompoundTag data) implements CustomPacketPayload {
    public static final Type<PacketFarmingProfilesData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_profiles_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingProfilesData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeNbt(msg.data() == null ? new CompoundTag() : msg.data()),
                    buf -> {
                        CompoundTag tag = buf.readNbt();
                        if (tag == null) tag = new CompoundTag();
                        return new PacketFarmingProfilesData(tag);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
