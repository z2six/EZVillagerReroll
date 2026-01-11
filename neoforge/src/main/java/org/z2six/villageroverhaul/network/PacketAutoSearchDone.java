// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketAutoSearchDone.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.codec.StreamCodec;
import org.z2six.villageroverhaul.Constants;

/**
 * Sent server -> client when an auto-search task completes (a requested item was found).
 * Client uses this to auto-close BusyVillagerScreen if it is open for that villager.
 */
public record PacketAutoSearchDone(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketAutoSearchDone> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_search_done"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAutoSearchDone> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketAutoSearchDone decode(RegistryFriendlyByteBuf buf) {
            return new PacketAutoSearchDone(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketAutoSearchDone msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
