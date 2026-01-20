// neoforge/src/main/java/org/z2six/villageroverhaul/network/history/PacketVillagerHistoryQuery.java
package org.z2six.villageroverhaul.network.history;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server request for villager history snapshot.
 */
public record PacketVillagerHistoryQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerHistoryQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_history_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerHistoryQuery> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerHistoryQuery decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    return new PacketVillagerHistoryQuery(id);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerHistoryQuery msg) {
                    buf.writeVarInt(msg.villagerEntityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

