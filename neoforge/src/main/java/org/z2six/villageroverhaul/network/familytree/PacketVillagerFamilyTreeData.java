package org.z2six.villageroverhaul.network.familytree;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerFamilyTreeData(
        int villagerEntityId,
        boolean ok,
        List<Node> nodes,
        List<Relation> relations
) implements CustomPacketPayload {

    public record Node(
            long uuidMsb,
            long uuidLsb,
            String firstName,
            String lastName,
            int generosity,
            int timeliness,
            int intellect,
            int hoarder,
            int vitality,
            int agility,
            int strength,
            int armor,
            int motivation,
            int efficiency,
            int plantWhisperer,
            int ranger,
            int x,
            int y,
            boolean selected
    ) {}

    public record Relation(
            long childMsb,
            long childLsb,
            long parentAMsb,
            long parentALsb,
            long parentBMsb,
            long parentBLsb
    ) {}

    public static final Type<PacketVillagerFamilyTreeData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_family_tree_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerFamilyTreeData> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerFamilyTreeData decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    boolean ok = buf.readBoolean();

                    int nodeCount = buf.readVarInt();
                    List<Node> nodes = new ArrayList<>(nodeCount);
                    for (int i = 0; i < nodeCount; i++) {
                        nodes.add(new Node(
                                buf.readLong(),
                                buf.readLong(),
                                buf.readUtf(),
                                buf.readUtf(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readVarInt(),
                                buf.readBoolean()
                        ));
                    }

                    int relationCount = buf.readVarInt();
                    List<Relation> relations = new ArrayList<>(relationCount);
                    for (int i = 0; i < relationCount; i++) {
                        relations.add(new Relation(
                                buf.readLong(),
                                buf.readLong(),
                                buf.readLong(),
                                buf.readLong(),
                                buf.readLong(),
                                buf.readLong()
                        ));
                    }

                    return new PacketVillagerFamilyTreeData(entityId, ok, nodes, relations);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerFamilyTreeData msg) {
                    buf.writeVarInt(msg.villagerEntityId());
                    buf.writeBoolean(msg.ok());

                    List<Node> nodes = msg.nodes() == null ? List.of() : msg.nodes();
                    buf.writeVarInt(nodes.size());
                    for (Node node : nodes) {
                        buf.writeLong(node.uuidMsb());
                        buf.writeLong(node.uuidLsb());
                        buf.writeUtf(node.firstName() == null ? "" : node.firstName());
                        buf.writeUtf(node.lastName() == null ? "" : node.lastName());
                        buf.writeVarInt(node.generosity());
                        buf.writeVarInt(node.timeliness());
                        buf.writeVarInt(node.intellect());
                        buf.writeVarInt(node.hoarder());
                        buf.writeVarInt(node.vitality());
                        buf.writeVarInt(node.agility());
                        buf.writeVarInt(node.strength());
                        buf.writeVarInt(node.armor());
                        buf.writeVarInt(node.motivation());
                        buf.writeVarInt(node.efficiency());
                        buf.writeVarInt(node.plantWhisperer());
                        buf.writeVarInt(node.ranger());
                        buf.writeVarInt(node.x());
                        buf.writeVarInt(node.y());
                        buf.writeBoolean(node.selected());
                    }

                    List<Relation> relations = msg.relations() == null ? List.of() : msg.relations();
                    buf.writeVarInt(relations.size());
                    for (Relation relation : relations) {
                        buf.writeLong(relation.childMsb());
                        buf.writeLong(relation.childLsb());
                        buf.writeLong(relation.parentAMsb());
                        buf.writeLong(relation.parentALsb());
                        buf.writeLong(relation.parentBMsb());
                        buf.writeLong(relation.parentBLsb());
                    }
                }
            };

    public static PacketVillagerFamilyTreeData missing(int entityId) {
        return new PacketVillagerFamilyTreeData(entityId, false, List.of(), List.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
