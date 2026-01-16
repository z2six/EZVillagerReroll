// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketContinueAutoSearch.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client presses "Continue" on the Busy screen.
 * Server does not change state; this is mainly for symmetry/logging and future extension.
 */
public record PacketContinueAutoSearch(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketContinueAutoSearch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "continue_auto_search"));

    public static final StreamCodec<FriendlyByteBuf, PacketContinueAutoSearch> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketContinueAutoSearch decode(FriendlyByteBuf buf) {
            return new PacketContinueAutoSearch(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketContinueAutoSearch msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
