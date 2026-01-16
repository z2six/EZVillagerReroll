// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketRerollCooldownQuery.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: ask for current cooldown remaining ticks for the currently open MerchantMenu villager.
 * Server resolves container + villager server-side and responds with PacketRerollCooldownState.
 */
public record PacketRerollCooldownQuery() implements CustomPacketPayload {

    public static final Type<PacketRerollCooldownQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cooldown_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketRerollCooldownQuery> STREAM_CODEC =
            StreamCodec.unit(new PacketRerollCooldownQuery());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
