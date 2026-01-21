// neoforge/src/main/java/org/z2six/villageroverhaul/network/respawn/PacketRespawnExecute.java
package org.z2six.villageroverhaul.network.respawn;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client -> Server: attempt to respawn a snapshot at the given anchor position.
 */
public record PacketRespawnExecute(
        int anchorX, int anchorY, int anchorZ,
        long respawnMsb, long respawnLsb
) implements CustomPacketPayload {

    public static final Type<PacketRespawnExecute> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "respawn_execute"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketRespawnExecute> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketRespawnExecute decode(RegistryFriendlyByteBuf buf) {
                    int x = 0, y = 0, z = 0;
                    long msb = 0L, lsb = 0L;
                    try {
                        x = buf.readInt();
                        y = buf.readInt();
                        z = buf.readInt();
                        msb = buf.readLong();
                        lsb = buf.readLong();
                    } catch (Throwable ignored) {}
                    return new PacketRespawnExecute(x, y, z, msb, lsb);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketRespawnExecute msg) {
                    buf.writeInt(msg.anchorX());
                    buf.writeInt(msg.anchorY());
                    buf.writeInt(msg.anchorZ());
                    buf.writeLong(msg.respawnMsb());
                    buf.writeLong(msg.respawnLsb());
                }
            };

    public UUID respawnId() {
        return new UUID(respawnMsb, respawnLsb);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

