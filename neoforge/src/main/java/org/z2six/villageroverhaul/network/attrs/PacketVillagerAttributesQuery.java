// neoforge\src\main\java\org\z2six\villageroverhaul\network\attrs\PacketVillagerAttributesQuery.java
package org.z2six.villageroverhaul.network.attrs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server request for attribute values for a specific entityId.
 *
 * We fetch attributes on the server since clients may not have server-modified base values.
 */
public record PacketVillagerAttributesQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerAttributesQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_attributes_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerAttributesQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerAttributesQuery::villagerEntityId,
                    PacketVillagerAttributesQuery::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

