// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketCombatSettingsSync.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketCombatSettingsSync(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCombatSettingsSync> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "combat_settings_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCombatSettingsSync> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketCombatSettingsSync::villagerEntityId,
                    PacketCombatSettingsSync::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
