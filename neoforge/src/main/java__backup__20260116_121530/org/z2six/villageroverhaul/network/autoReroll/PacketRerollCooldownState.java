// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketRerollCooldownState.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: authoritative cooldown state for the current MerchantMenu.
 *
 * containerId: ties the state to the open merchant container
 * ticksRemaining: how many ticks until reroll is allowed again (0 means allowed now)
 * cooldownTicksConfigured: server config value (informational; useful for UI/diagnostics)
 */
public record PacketRerollCooldownState(int containerId, int ticksRemaining, int cooldownTicksConfigured) implements CustomPacketPayload {

    public static final Type<PacketRerollCooldownState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cooldown_state"));

    public static final StreamCodec<FriendlyByteBuf, PacketRerollCooldownState> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketRerollCooldownState decode(FriendlyByteBuf buf) {
            int cid = buf.readVarInt();
            int rem = buf.readVarInt();
            int cfg = buf.readVarInt();
            if (rem < 0) rem = 0;
            if (cfg < 0) cfg = 0;
            return new PacketRerollCooldownState(cid, rem, cfg);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketRerollCooldownState p) {
            buf.writeVarInt(Math.max(0, p.containerId()));
            buf.writeVarInt(Math.max(0, p.ticksRemaining()));
            buf.writeVarInt(Math.max(0, p.cooldownTicksConfigured()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
