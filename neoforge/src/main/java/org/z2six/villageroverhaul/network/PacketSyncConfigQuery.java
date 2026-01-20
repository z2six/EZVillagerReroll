// neoforge\src\main\java\org\z2six\villageroverhaul\network\PacketSyncConfigQuery.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client asks the server to resend {@link PacketSyncConfig}.
 * Useful when server config is changed while the client is connected.
 */
public record PacketSyncConfigQuery() implements CustomPacketPayload {

    public static final Type<PacketSyncConfigQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_config_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketSyncConfigQuery> STREAM_CODEC =
            StreamCodec.unit(new PacketSyncConfigQuery());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

