// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketCombatSettingsData.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketCombatSettingsData(int villagerEntityId, boolean global, CompoundTag settings) implements CustomPacketPayload {

    public static final Type<PacketCombatSettingsData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "combat_settings_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCombatSettingsData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketCombatSettingsData::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketCombatSettingsData::global,
                    ByteBufCodecs.COMPOUND_TAG, PacketCombatSettingsData::settings,
                    PacketCombatSettingsData::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
