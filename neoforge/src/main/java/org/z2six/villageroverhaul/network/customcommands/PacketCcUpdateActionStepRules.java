package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> Server: update withdraw/deposit item rules (item id -> count) for a saved chest step.
 */
public record PacketCcUpdateActionStepRules(
        int villagerEntityId,
        int actionIndex,
        int stepIndex,
        List<Rule> rules
) implements CustomPacketPayload {

    public record Rule(String itemId, int count) {}

    public static final Type<PacketCcUpdateActionStepRules> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_update_step_rules"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcUpdateActionStepRules> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.actionIndex());
                        buf.writeVarInt(msg.stepIndex());
                        List<Rule> rr = msg.rules() == null ? List.of() : msg.rules();
                        int n = Math.min(256, rr.size());
                        buf.writeVarInt(n);
                        for (int i = 0; i < n; i++) {
                            Rule r = rr.get(i);
                            String id = r == null || r.itemId() == null ? "" : r.itemId();
                            buf.writeUtf(id, 128);
                            buf.writeVarInt(r == null ? 0 : r.count());
                        }
                    },
                    (buf) -> {
                        int vid = buf.readVarInt();
                        int ai = buf.readVarInt();
                        int si = buf.readVarInt();
                        int n = buf.readVarInt();
                        n = Math.max(0, Math.min(256, n));
                        List<Rule> rr = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            String id = buf.readUtf(128);
                            int c = buf.readVarInt();
                            rr.add(new Rule(id, c));
                        }
                        return new PacketCcUpdateActionStepRules(vid, ai, si, rr);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

