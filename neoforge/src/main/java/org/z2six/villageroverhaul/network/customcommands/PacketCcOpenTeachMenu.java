package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: open the "Teach" action menu while in teaching mode.
 */
public record PacketCcOpenTeachMenu(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCcOpenTeachMenu> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_open_teach_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcOpenTeachMenu> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeVarInt(msg.villagerEntityId()),
                    (buf) -> new PacketCcOpenTeachMenu(buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

