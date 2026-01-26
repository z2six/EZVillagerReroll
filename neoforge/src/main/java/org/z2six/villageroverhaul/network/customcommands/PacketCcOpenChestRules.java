package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: after recording a withdraw/deposit chest, open the item rules editor for that step.
 */
public record PacketCcOpenChestRules(
        int villagerEntityId,
        int stepIndex,
        int kind // 1=withdraw, 2=deposit
) implements CustomPacketPayload {

    public static final Type<PacketCcOpenChestRules> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_open_chest_rules"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcOpenChestRules> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.stepIndex());
                        buf.writeVarInt(msg.kind());
                    },
                    (buf) -> new PacketCcOpenChestRules(
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

