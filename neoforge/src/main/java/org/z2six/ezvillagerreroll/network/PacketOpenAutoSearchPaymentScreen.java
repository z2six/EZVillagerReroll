// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketOpenAutoSearchPaymentScreen.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

/**
 * Server -> Client: open the "auto-search payment" screen (settlement pending).
 *
 * Client should show:
 * - hourlyCost
 * - elapsedSeconds
 * - finalCost
 * Buttons:
 * - Pay -> send PacketPayAutoSearchSettlement(villagerEntityId)
 * - Cancel -> send PacketDeclineAutoSearchSettlement(villagerEntityId)
 */
public record PacketOpenAutoSearchPaymentScreen(
        int villagerEntityId,
        int hourlyCost,
        int finalCost,
        int elapsedTicks
) implements CustomPacketPayload {

    public static final Type<PacketOpenAutoSearchPaymentScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_auto_search_payment"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenAutoSearchPaymentScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenAutoSearchPaymentScreen decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int hourly = buf.readVarInt();
                int fin = buf.readVarInt();
                int ticks = buf.readVarInt();
                return new PacketOpenAutoSearchPaymentScreen(id, Math.max(0, hourly), Math.max(0, fin), Math.max(0, ticks));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen decode failed", t);
                return new PacketOpenAutoSearchPaymentScreen(-1, 0, 0, 0);
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketOpenAutoSearchPaymentScreen msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                buf.writeVarInt(Math.max(0, msg.hourlyCost()));
                buf.writeVarInt(Math.max(0, msg.finalCost()));
                buf.writeVarInt(Math.max(0, msg.elapsedTicks()));
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] PacketOpenAutoSearchPaymentScreen encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
