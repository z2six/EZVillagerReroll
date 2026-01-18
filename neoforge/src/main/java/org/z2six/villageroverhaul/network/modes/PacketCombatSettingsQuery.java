// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketCombatSettingsQuery.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketCombatSettingsQuery(int villagerEntityId, boolean global) implements CustomPacketPayload {

    public static final Type<PacketCombatSettingsQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "combat_settings_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCombatSettingsQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketCombatSettingsQuery::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketCombatSettingsQuery::global,
                    PacketCombatSettingsQuery::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
