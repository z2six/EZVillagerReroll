package org.z2six.villageroverhaul.network.farming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketFarmingProfileUpsert(String name, CompoundTag settings) implements CustomPacketPayload {
    private static final int MAX_NAME_LEN = 48;

    public static final Type<PacketFarmingProfileUpsert> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "farming_profiles_upsert"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketFarmingProfileUpsert> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeUtf(msg.name() == null ? "" : msg.name(), MAX_NAME_LEN);
                        buf.writeNbt(msg.settings() == null ? new CompoundTag() : msg.settings());
                    },
                    buf -> {
                        String name = buf.readUtf(MAX_NAME_LEN);
                        CompoundTag tag = buf.readNbt();
                        if (tag == null) tag = new CompoundTag();
                        return new PacketFarmingProfileUpsert(name, tag);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
