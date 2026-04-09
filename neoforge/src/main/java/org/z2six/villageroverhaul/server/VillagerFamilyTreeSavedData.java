package org.z2six.villageroverhaul.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class VillagerFamilyTreeSavedData extends SavedData {
    public static final String DATA_NAME = "villageroverhaul_family_tree";

    public static final class NodeData {
        public UUID villagerUuid;
        public String firstName = "";
        public String lastName = "";
        public int genderId = VillagerGenderService.GENDER_UNKNOWN;
        public int generosity;
        public int timeliness;
        public int intellect;
        public int hoarder;
        public int vitality;
        public int agility;
        public int strength;
        public int armor;
        public int motivation;
        public int efficiency;
        public int plantWhisperer;
        public int ranger;
    }

    public static final class ParentLink {
        public UUID childUuid;
        public UUID parentAUuid;
        public UUID parentBUuid;
        public long recordedAtGameTime;
    }

    private final Map<UUID, NodeData> nodes = new LinkedHashMap<>();
    private final Map<UUID, ParentLink> parentsByChild = new LinkedHashMap<>();

    public Map<UUID, NodeData> nodes() {
        return nodes;
    }

    public Map<UUID, ParentLink> parentsByChild() {
        return parentsByChild;
    }

    public static VillagerFamilyTreeSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                new Factory<>(VillagerFamilyTreeSavedData::new, VillagerFamilyTreeSavedData::load),
                DATA_NAME
        );
    }

    public static VillagerFamilyTreeSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        VillagerFamilyTreeSavedData data = new VillagerFamilyTreeSavedData();
        if (tag == null) {
            return data;
        }

        if (tag.contains("nodes", Tag.TAG_LIST)) {
            ListTag nodesTag = tag.getList("nodes", Tag.TAG_COMPOUND);
            for (int i = 0; i < nodesTag.size(); i++) {
                CompoundTag nodeTag = nodesTag.getCompound(i);
                UUID uuid = readUuid(nodeTag, "uuid");
                if (uuid == null) {
                    continue;
                }
                NodeData node = new NodeData();
                node.villagerUuid = uuid;
                node.firstName = nodeTag.getString("firstName");
                node.lastName = nodeTag.getString("lastName");
                node.genderId = nodeTag.contains("genderId", Tag.TAG_INT)
                        ? nodeTag.getInt("genderId")
                        : VillagerGenderService.GENDER_UNKNOWN;
                node.generosity = nodeTag.getInt("generosity");
                node.timeliness = nodeTag.getInt("timeliness");
                node.intellect = nodeTag.getInt("intellect");
                node.hoarder = nodeTag.getInt("hoarder");
                node.vitality = nodeTag.getInt("vitality");
                node.agility = nodeTag.getInt("agility");
                node.strength = nodeTag.getInt("strength");
                node.armor = nodeTag.getInt("armor");
                node.motivation = nodeTag.getInt("motivation");
                node.efficiency = nodeTag.getInt("efficiency");
                node.plantWhisperer = nodeTag.getInt("plantWhisperer");
                node.ranger = nodeTag.getInt("ranger");
                data.nodes.put(uuid, node);
            }
        }

        if (tag.contains("parents", Tag.TAG_LIST)) {
            ListTag parentsTag = tag.getList("parents", Tag.TAG_COMPOUND);
            for (int i = 0; i < parentsTag.size(); i++) {
                CompoundTag linkTag = parentsTag.getCompound(i);
                UUID child = readUuid(linkTag, "child");
                UUID parentA = readUuid(linkTag, "parentA");
                UUID parentB = readUuid(linkTag, "parentB");
                if (child == null || parentA == null || parentB == null) {
                    continue;
                }
                ParentLink link = new ParentLink();
                link.childUuid = child;
                link.parentAUuid = parentA;
                link.parentBUuid = parentB;
                link.recordedAtGameTime = Math.max(0L, linkTag.getLong("recordedAtGameTime"));
                data.parentsByChild.put(child, link);
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag nodesTag = new ListTag();
        for (NodeData node : nodes.values()) {
            if (node == null || node.villagerUuid == null) {
                continue;
            }
            CompoundTag nodeTag = new CompoundTag();
            writeUuid(nodeTag, "uuid", node.villagerUuid);
            nodeTag.putString("firstName", node.firstName == null ? "" : node.firstName);
            nodeTag.putString("lastName", node.lastName == null ? "" : node.lastName);
            nodeTag.putInt("genderId", node.genderId);
            nodeTag.putInt("generosity", node.generosity);
            nodeTag.putInt("timeliness", node.timeliness);
            nodeTag.putInt("intellect", node.intellect);
            nodeTag.putInt("hoarder", node.hoarder);
            nodeTag.putInt("vitality", node.vitality);
            nodeTag.putInt("agility", node.agility);
            nodeTag.putInt("strength", node.strength);
            nodeTag.putInt("armor", node.armor);
            nodeTag.putInt("motivation", node.motivation);
            nodeTag.putInt("efficiency", node.efficiency);
            nodeTag.putInt("plantWhisperer", node.plantWhisperer);
            nodeTag.putInt("ranger", node.ranger);
            nodesTag.add(nodeTag);
        }
        tag.put("nodes", nodesTag);

        ListTag parentsTag = new ListTag();
        for (ParentLink link : parentsByChild.values()) {
            if (link == null || link.childUuid == null || link.parentAUuid == null || link.parentBUuid == null) {
                continue;
            }
            CompoundTag linkTag = new CompoundTag();
            writeUuid(linkTag, "child", link.childUuid);
            writeUuid(linkTag, "parentA", link.parentAUuid);
            writeUuid(linkTag, "parentB", link.parentBUuid);
            linkTag.putLong("recordedAtGameTime", Math.max(0L, link.recordedAtGameTime));
            parentsTag.add(linkTag);
        }
        tag.put("parents", parentsTag);
        return tag;
    }

    private static void writeUuid(CompoundTag tag, String key, UUID uuid) {
        if (tag == null || key == null || uuid == null) {
            return;
        }
        tag.putUUID(key, uuid);
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        try {
            return tag != null && tag.hasUUID(key) ? tag.getUUID(key) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
