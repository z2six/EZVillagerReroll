package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: save the currently taught action (meta + recorded steps in the session).
 */
public record PacketCcSaveTaughtAction(
        int villagerEntityId,
        int editIndex,
        String title,
        String command,
        boolean caseSensitive,
        boolean chain,
        String description,
        int timeoutSeconds,
        int retryAfterSeconds,
        int stopAfterRetries
) implements CustomPacketPayload {

    public static final Type<PacketCcSaveTaughtAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_save_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcSaveTaughtAction> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.editIndex());
                        buf.writeUtf(msg.title() == null ? "" : msg.title(), 64);
                        buf.writeUtf(msg.command() == null ? "" : msg.command(), 64);
                        buf.writeBoolean(msg.caseSensitive());
                        buf.writeBoolean(msg.chain());
                        buf.writeUtf(msg.description() == null ? "" : msg.description(), 256);
                        buf.writeVarInt(msg.timeoutSeconds());
                        buf.writeVarInt(msg.retryAfterSeconds());
                        buf.writeVarInt(msg.stopAfterRetries());
                    },
                    (buf) -> new PacketCcSaveTaughtAction(
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readUtf(64),
                            buf.readUtf(64),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readUtf(256),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
