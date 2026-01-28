package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: set whether a taught macro should cancel execution to engage the active combat mode.
 */
public record PacketCcSetCombatOverride(int villagerEntityId, int actionIndex, boolean enabled) implements CustomPacketPayload {
    public static final Type<PacketCcSetCombatOverride> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_combat_override"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcSetCombatOverride> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.actionIndex());
                        buf.writeBoolean(msg.enabled());
                    },
                    (buf) -> new PacketCcSetCombatOverride(buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

