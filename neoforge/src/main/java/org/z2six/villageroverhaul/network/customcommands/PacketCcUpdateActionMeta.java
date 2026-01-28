package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: update an existing taught action's meta (title/command/case/desc/timeout/retry).
 * Does not alter the recorded steps.
 */
public record PacketCcUpdateActionMeta(
        int villagerEntityId,
        int actionIndex,
        String title,
        String command,
        boolean caseSensitive,
        boolean chain,
        boolean anyone,
        String description,
        int timeoutSeconds,
        int retryAfterSeconds,
        int stopAfterRetries
) implements CustomPacketPayload {

    public static final Type<PacketCcUpdateActionMeta> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_update_meta"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcUpdateActionMeta> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.actionIndex());
                        buf.writeUtf(msg.title() == null ? "" : msg.title(), 64);
                        buf.writeUtf(msg.command() == null ? "" : msg.command(), 64);
                        buf.writeBoolean(msg.caseSensitive());
                        buf.writeBoolean(msg.chain());
                        buf.writeBoolean(msg.anyone());
                        buf.writeUtf(msg.description() == null ? "" : msg.description(), 256);
                        buf.writeVarInt(msg.timeoutSeconds());
                        buf.writeVarInt(msg.retryAfterSeconds());
                        buf.writeVarInt(msg.stopAfterRetries());
                    },
                    (buf) -> new PacketCcUpdateActionMeta(
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readUtf(64),
                            buf.readUtf(64),
                            buf.readBoolean(),
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
