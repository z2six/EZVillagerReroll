// PacketPatrolOpenGui.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketPatrolOpenGui.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketPatrolOpenGui(int villagerEntityId, boolean canOpen, int waypointCount, boolean hasPatrolData) implements CustomPacketPayload {

    public static final Type<PacketPatrolOpenGui> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_open_gui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolOpenGui> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketPatrolOpenGui::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketPatrolOpenGui::canOpen,
                    ByteBufCodecs.VAR_INT, PacketPatrolOpenGui::waypointCount,
                    ByteBufCodecs.BOOL, PacketPatrolOpenGui::hasPatrolData,
                    PacketPatrolOpenGui::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
