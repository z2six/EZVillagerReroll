package org.z2six.villageroverhaul.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;
import org.z2six.villageroverhaul.network.familytree.PacketVillagerFamilyTreeData;

public final class VillagerFamilyTreeService {
    private static final int NODE_W = 110;
    private static final int NODE_GAP_X = 28;
    private static final int NODE_GAP_Y = 84;

    private VillagerFamilyTreeService() {
    }

    public static boolean recordBreeding(Villager child, Villager parentA, Villager parentB) {
        try {
            if (!(child.level() instanceof ServerLevel level)) {
                return false;
            }
            VillagerGenderService.ensureAssigned(child);
            VillagerGenderService.ensureAssigned(parentA);
            VillagerGenderService.ensureAssigned(parentB);
            VillagerNameStateService.tryAdoptExistingName(child);
            VillagerNameStateService.tryAdoptExistingName(parentA);
            VillagerNameStateService.tryAdoptExistingName(parentB);
            if (!VillagerNameStateService.isTracked(child)
                    || !VillagerNameStateService.isTracked(parentA)
                    || !VillagerNameStateService.isTracked(parentB)) {
                return false;
            }

            VillagerFamilyTreeSavedData data = VillagerFamilyTreeSavedData.get(level.getServer().overworld());
            putNode(data, child);
            putNode(data, parentA);
            putNode(data, parentB);

            VillagerFamilyTreeSavedData.ParentLink existing = data.parentsByChild().get(child.getUUID());
            if (existing != null
                    && child.getUUID().equals(existing.childUuid)
                    && parentA.getUUID().equals(existing.parentAUuid)
                    && parentB.getUUID().equals(existing.parentBUuid)) {
                return false;
            }

            VillagerFamilyTreeSavedData.ParentLink link = new VillagerFamilyTreeSavedData.ParentLink();
            link.childUuid = child.getUUID();
            link.parentAUuid = parentA.getUUID();
            link.parentBUuid = parentB.getUUID();
            link.recordedAtGameTime = Math.max(0L, level.getGameTime());
            data.parentsByChild().put(link.childUuid, link);
            data.setDirty();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static PacketVillagerFamilyTreeData snapshot(Villager villager) {
        try {
            VillagerNameStateService.tryAdoptExistingName(villager);
            if (villager == null || !VillagerNameStateService.isTracked(villager)) {
                return PacketVillagerFamilyTreeData.missing(villager == null ? -1 : villager.getId());
            }
            if (!(villager.level() instanceof ServerLevel level)) {
                return PacketVillagerFamilyTreeData.missing(villager.getId());
            }

            VillagerFamilyTreeSavedData data = VillagerFamilyTreeSavedData.get(level.getServer().overworld());
            putNode(data, villager);
            UUID selected = villager.getUUID();

            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild = data.parentsByChild();
            Map<UUID, Set<UUID>> childrenByParent = buildChildrenIndex(parentsByChild.values());

            Set<UUID> component = collectComponent(selected, parentsByChild, childrenByParent);
            if (component.isEmpty()) {
                component.add(selected);
            }
            refreshLoadedComponentNodes(level.getServer(), data, component);

            Map<UUID, Integer> absoluteDepths = assignAbsoluteDepths(component, parentsByChild);
            int selectedDepth = absoluteDepths.getOrDefault(selected, 0);
            Map<UUID, Integer> generations = new HashMap<>();
            for (UUID uuid : component) {
                generations.put(uuid, absoluteDepths.getOrDefault(uuid, 0) - selectedDepth);
            }

            Map<UUID, Double> rowOrder = assignRowOrder(component, absoluteDepths, parentsByChild, childrenByParent, villager, data);
            Map<Integer, List<UUID>> rows = new HashMap<>();
            for (UUID uuid : component) {
                rows.computeIfAbsent(generations.getOrDefault(uuid, 0), ignored -> new ArrayList<>()).add(uuid);
            }

            Comparator<UUID> rowComparator = Comparator
                    .comparingDouble((UUID uuid) -> rowOrder.getOrDefault(uuid, 0.0D))
                    .thenComparing((UUID uuid) -> !uuid.equals(selected))
                    .thenComparing(uuid -> nodeSortKey(uuid, villager, data));
            for (List<UUID> row : rows.values()) {
                row.sort(rowComparator);
            }

            List<PacketVillagerFamilyTreeData.Node> nodes = new ArrayList<>(component.size());
            for (Map.Entry<Integer, List<UUID>> entry : rows.entrySet()) {
                int generation = entry.getKey();
                List<UUID> row = entry.getValue();
                int rowWidth = row.size() * NODE_W + Math.max(0, row.size() - 1) * NODE_GAP_X;
                int startX = -(rowWidth / 2);

                for (int i = 0; i < row.size(); i++) {
                    UUID uuid = row.get(i);
                    NameParts parts = resolveParts(uuid, villager, data);
                    NodeStats stats = resolveStats(uuid, villager, data);
                    int x = startX + i * (NODE_W + NODE_GAP_X);
                    int y = generation * NODE_GAP_Y;
                    nodes.add(new PacketVillagerFamilyTreeData.Node(
                            uuid.getMostSignificantBits(),
                            uuid.getLeastSignificantBits(),
                            parts.firstName(),
                            parts.lastName(),
                            resolveGenderId(uuid, villager, data),
                            stats.generosity(),
                            stats.timeliness(),
                            stats.intellect(),
                            stats.hoarder(),
                            stats.vitality(),
                            stats.agility(),
                            stats.strength(),
                            stats.armor(),
                            stats.motivation(),
                            stats.efficiency(),
                            stats.plantWhisperer(),
                            stats.ranger(),
                            x,
                            y,
                            uuid.equals(selected)
                    ));
                }
            }

            List<PacketVillagerFamilyTreeData.Relation> relations = new ArrayList<>();
            for (UUID childUuid : component) {
                VillagerFamilyTreeSavedData.ParentLink link = parentsByChild.get(childUuid);
                if (link == null || link.parentAUuid == null || link.parentBUuid == null) {
                    continue;
                }
                if (!component.contains(link.parentAUuid) || !component.contains(link.parentBUuid)) {
                    continue;
                }
                relations.add(new PacketVillagerFamilyTreeData.Relation(
                        childUuid.getMostSignificantBits(),
                        childUuid.getLeastSignificantBits(),
                        link.parentAUuid.getMostSignificantBits(),
                        link.parentAUuid.getLeastSignificantBits(),
                        link.parentBUuid.getMostSignificantBits(),
                        link.parentBUuid.getLeastSignificantBits()
                ));
            }

            return new PacketVillagerFamilyTreeData(villager.getId(), true, nodes, relations);
        } catch (Throwable ignored) {
            return PacketVillagerFamilyTreeData.missing(villager == null ? -1 : villager.getId());
        }
    }

    public static List<String> collectUniqueLastNames(Villager villager) {
        try {
            VillagerNameStateService.tryAdoptExistingName(villager);
            if (villager == null || !VillagerNameStateService.isTracked(villager)) {
                return List.of();
            }
            if (!(villager.level() instanceof ServerLevel level)) {
                return List.of();
            }

            VillagerFamilyTreeSavedData data = VillagerFamilyTreeSavedData.get(level.getServer().overworld());
            putNode(data, villager);

            UUID selected = villager.getUUID();
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild = data.parentsByChild();
            Map<UUID, Set<UUID>> childrenByParent = buildChildrenIndex(parentsByChild.values());

            Set<UUID> component = collectComponent(selected, parentsByChild, childrenByParent);
            if (component.isEmpty()) {
                component.add(selected);
            }
            refreshLoadedComponentNodes(level.getServer(), data, component);

            String currentLastName = VillagerNameStateService.getTrackedLastName(villager);
            List<String> names = new ArrayList<>();
            addUniqueLastName(names, currentLastName);

            List<String> discovered = new ArrayList<>();
            for (UUID uuid : component) {
                String lastName = null;
                if (uuid != null && uuid.equals(selected)) {
                    lastName = currentLastName;
                } else {
                    VillagerFamilyTreeSavedData.NodeData node = data.nodes().get(uuid);
                    if (node != null) {
                        lastName = node.lastName;
                    }
                }
                addUniqueLastName(discovered, lastName);
            }
            discovered.sort(String.CASE_INSENSITIVE_ORDER);
            for (String lastName : discovered) {
                addUniqueLastName(names, lastName);
            }

            return List.copyOf(names);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static boolean changeLastNameFromFamilyTree(Villager villager, String requestedLastName) {
        try {
            VillagerNameStateService.tryAdoptExistingName(villager);
            if (villager == null || requestedLastName == null || requestedLastName.isBlank()) {
                return false;
            }
            String firstName = VillagerNameStateService.getTrackedFirstName(villager);
            if (firstName == null || firstName.isBlank()) {
                return false;
            }

            String allowedLastName = findAllowedLastName(collectUniqueLastNames(villager), requestedLastName);
            if (allowedLastName == null) {
                return false;
            }

            VillagerNameStateService.applyTrackedName(villager, firstName, allowedLastName);
            if (villager.level() instanceof ServerLevel level) {
                VillagerFamilyTreeSavedData data = VillagerFamilyTreeSavedData.get(level.getServer().overworld());
                putNode(data, villager);
                data.setDirty();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean changeLastNameToGenerated(Villager villager) {
        try {
            VillagerNameStateService.tryAdoptExistingName(villager);
            if (villager == null) {
                return false;
            }
            String firstName = VillagerNameStateService.getTrackedFirstName(villager);
            if (firstName == null || firstName.isBlank()) {
                return false;
            }

            List<String> existing = collectUniqueLastNames(villager);
            String generatedLastName = generateUnusedLastName(existing);
            if (generatedLastName == null || generatedLastName.isBlank()) {
                return false;
            }

            VillagerNameStateService.applyTrackedName(villager, firstName, generatedLastName);
            if (villager.level() instanceof ServerLevel level) {
                VillagerFamilyTreeSavedData data = VillagerFamilyTreeSavedData.get(level.getServer().overworld());
                putNode(data, villager);
                data.setDirty();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addUniqueLastName(List<String> names, @Nullable String lastName) {
        if (names == null || lastName == null) {
            return;
        }
        String normalized = lastName.trim();
        if (normalized.isEmpty()) {
            return;
        }
        for (String existing : names) {
            if (existing != null && existing.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        names.add(normalized);
    }

    private static @Nullable String findAllowedLastName(List<String> allowed, String requestedLastName) {
        if (allowed == null || requestedLastName == null) {
            return null;
        }
        String normalized = requestedLastName.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        for (String lastName : allowed) {
            if (lastName != null && lastName.equalsIgnoreCase(normalized)) {
                return lastName.trim();
            }
        }
        return null;
    }

    private static @Nullable String generateUnusedLastName(List<String> existing) {
        for (int i = 0; i < 64; i++) {
            String candidate = VillagerNameGenerator.createLastName(UUID.randomUUID());
            if (!containsLastName(existing, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsLastName(List<String> names, String candidate) {
        if (names == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalized = candidate.trim();
        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static void putNode(VillagerFamilyTreeSavedData data, Villager villager) {
        String firstName = VillagerNameStateService.getTrackedFirstName(villager);
        String lastName = VillagerNameStateService.getTrackedLastName(villager);
        if (firstName == null || lastName == null) {
            return;
        }
        VillagerStatsService.StatSnapshot stats = VillagerStatsService.snapshot(villager);
        int genderId = VillagerGenderService.ensureAssigned(villager);

        VillagerFamilyTreeSavedData.NodeData node = data.nodes().get(villager.getUUID());
        if (node == null) {
            node = new VillagerFamilyTreeSavedData.NodeData();
            node.villagerUuid = villager.getUUID();
            data.nodes().put(node.villagerUuid, node);
        }
        if (!firstName.equals(node.firstName)
                || !lastName.equals(node.lastName)
                || node.genderId != genderId
                || !matchesSnapshot(node, stats)) {
            node.firstName = firstName;
            node.lastName = lastName;
            node.genderId = genderId;
            applySnapshot(node, stats);
            data.setDirty();
        }
    }

    private static void refreshLoadedComponentNodes(MinecraftServer server, VillagerFamilyTreeSavedData data, Set<UUID> component) {
        if (server == null || data == null || component == null || component.isEmpty()) {
            return;
        }
        for (UUID uuid : component) {
            Villager villager = findTrackedVillager(server, uuid);
            if (villager != null) {
                putNode(data, villager);
            }
        }
    }

    private static @Nullable Villager findTrackedVillager(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            try {
                if (!(level.getEntity(uuid) instanceof Villager villager)) {
                    continue;
                }
                VillagerNameStateService.tryAdoptExistingName(villager);
                if (VillagerNameStateService.isTracked(villager)) {
                    return villager;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Set<UUID> collectComponent(
            UUID selected,
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild,
            Map<UUID, Set<UUID>> childrenByParent
    ) {
        Set<UUID> visited = new LinkedHashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        queue.add(selected);

        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }

            VillagerFamilyTreeSavedData.ParentLink parentLink = parentsByChild.get(current);
            if (parentLink != null) {
                enqueue(queue, parentLink.parentAUuid);
                enqueue(queue, parentLink.parentBUuid);
            }

            for (UUID child : childrenByParent.getOrDefault(current, Set.of())) {
                enqueue(queue, child);
                VillagerFamilyTreeSavedData.ParentLink childLink = parentsByChild.get(child);
                if (childLink != null) {
                    enqueue(queue, childLink.parentAUuid);
                    enqueue(queue, childLink.parentBUuid);
                }
            }
        }

        return visited;
    }

    private static Map<UUID, Integer> assignAbsoluteDepths(
            Set<UUID> component,
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild
    ) {
        Map<UUID, Integer> memo = new HashMap<>();
        Set<UUID> visiting = new LinkedHashSet<>();
        for (UUID uuid : component) {
            computeAbsoluteDepth(uuid, component, parentsByChild, memo, visiting);
        }
        return memo;
    }

    private static int computeAbsoluteDepth(
            UUID uuid,
            Set<UUID> component,
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild,
            Map<UUID, Integer> memo,
            Set<UUID> visiting
    ) {
        Integer cached = memo.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(uuid)) {
            return 0;
        }

        VillagerFamilyTreeSavedData.ParentLink link = parentsByChild.get(uuid);
        int depth = 0;
        if (link != null) {
            int parentADepth = component.contains(link.parentAUuid)
                    ? computeAbsoluteDepth(link.parentAUuid, component, parentsByChild, memo, visiting)
                    : 0;
            int parentBDepth = component.contains(link.parentBUuid)
                    ? computeAbsoluteDepth(link.parentBUuid, component, parentsByChild, memo, visiting)
                    : 0;
            depth = Math.max(parentADepth, parentBDepth) + 1;
        }

        visiting.remove(uuid);
        memo.put(uuid, depth);
        return depth;
    }

    private static Map<UUID, Double> assignRowOrder(
            Set<UUID> component,
            Map<UUID, Integer> absoluteDepths,
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild,
            Map<UUID, Set<UUID>> childrenByParent,
            Villager selectedVillager,
            VillagerFamilyTreeSavedData data
    ) {
        Map<UUID, Double> order = new HashMap<>();
        int maxDepth = 0;
        for (int depth : absoluteDepths.values()) {
            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }

        for (int depth = 0; depth <= maxDepth; depth++) {
            List<UUID> row = new ArrayList<>();
            for (UUID uuid : component) {
                if (absoluteDepths.getOrDefault(uuid, 0) == depth) {
                    row.add(uuid);
                }
            }

            if (row.isEmpty()) {
                continue;
            }

            if (depth == 0) {
                row.sort(Comparator.comparing(uuid -> nodeSortKey(uuid, selectedVillager, data)));
            } else {
                row.sort(Comparator
                        .comparingDouble((UUID uuid) -> parentBarycenter(uuid, parentsByChild, order))
                        .thenComparing((UUID uuid) -> parentPairSortKey(uuid, parentsByChild, selectedVillager, data))
                        .thenComparingInt((UUID uuid) -> childCountSort(uuid, childrenByParent))
                        .thenComparing((UUID uuid) -> nodeSortKey(uuid, selectedVillager, data)));
            }

            for (int i = 0; i < row.size(); i++) {
                order.put(row.get(i), (double) i);
            }
        }

        return order;
    }

    private static double parentBarycenter(
            UUID uuid,
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild,
            Map<UUID, Double> order
    ) {
        VillagerFamilyTreeSavedData.ParentLink link = parentsByChild.get(uuid);
        if (link == null) {
            return Double.MAX_VALUE;
        }

        double sum = 0.0D;
        int count = 0;
        Double orderA = order.get(link.parentAUuid);
        if (orderA != null) {
            sum += orderA;
            count++;
        }
        Double orderB = order.get(link.parentBUuid);
        if (orderB != null) {
            sum += orderB;
            count++;
        }
        return count == 0 ? Double.MAX_VALUE : (sum / count);
    }

    private static int childCountSort(UUID uuid, Map<UUID, Set<UUID>> childrenByParent) {
        return -childrenByParent.getOrDefault(uuid, Set.of()).size();
    }

    private static String parentPairSortKey(
            UUID uuid,
            Map<UUID, VillagerFamilyTreeSavedData.ParentLink> parentsByChild,
            Villager selectedVillager,
            VillagerFamilyTreeSavedData data
    ) {
        VillagerFamilyTreeSavedData.ParentLink link = parentsByChild.get(uuid);
        if (link == null || link.parentAUuid == null || link.parentBUuid == null) {
            return "~";
        }

        String a = nodeSortKey(link.parentAUuid, selectedVillager, data);
        String b = nodeSortKey(link.parentBUuid, selectedVillager, data);
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static Map<UUID, Set<UUID>> buildChildrenIndex(Collection<VillagerFamilyTreeSavedData.ParentLink> parentLinks) {
        Map<UUID, Set<UUID>> childrenByParent = new HashMap<>();
        for (VillagerFamilyTreeSavedData.ParentLink link : parentLinks) {
            if (link == null || link.childUuid == null) {
                continue;
            }
            addChild(childrenByParent, link.parentAUuid, link.childUuid);
            addChild(childrenByParent, link.parentBUuid, link.childUuid);
        }
        return childrenByParent;
    }

    private static void addChild(Map<UUID, Set<UUID>> childrenByParent, @Nullable UUID parentUuid, UUID childUuid) {
        if (parentUuid == null) {
            return;
        }
        childrenByParent.computeIfAbsent(parentUuid, ignored -> new LinkedHashSet<>()).add(childUuid);
    }

    private static void enqueue(ArrayDeque<UUID> queue, @Nullable UUID uuid) {
        if (uuid != null) {
            queue.add(uuid);
        }
    }

    private static String nodeSortKey(UUID uuid, Villager selectedVillager, VillagerFamilyTreeSavedData data) {
        NameParts parts = resolveParts(uuid, selectedVillager, data);
        return (parts.lastName() + "|" + parts.firstName()).toLowerCase();
    }

    private static NameParts resolveParts(UUID uuid, Villager selectedVillager, VillagerFamilyTreeSavedData data) {
        if (uuid.equals(selectedVillager.getUUID())) {
            String first = VillagerNameStateService.getTrackedFirstName(selectedVillager);
            String last = VillagerNameStateService.getTrackedLastName(selectedVillager);
            if (first != null && last != null) {
                return new NameParts(first, last);
            }
        }

        VillagerFamilyTreeSavedData.NodeData node = data.nodes().get(uuid);
        if (node != null && node.firstName != null && !node.firstName.isBlank() && node.lastName != null && !node.lastName.isBlank()) {
            return new NameParts(node.firstName, node.lastName);
        }
        return new NameParts("Unknown", "Lineage");
    }

    private static NodeStats resolveStats(UUID uuid, Villager selectedVillager, VillagerFamilyTreeSavedData data) {
        if (uuid.equals(selectedVillager.getUUID()) && VillagerNameStateService.isTracked(selectedVillager)) {
            return NodeStats.from(VillagerStatsService.snapshot(selectedVillager));
        }

        VillagerFamilyTreeSavedData.NodeData node = data.nodes().get(uuid);
        if (node == null) {
            return NodeStats.empty();
        }
        return NodeStats.from(node);
    }

    private static int resolveGenderId(UUID uuid, Villager selectedVillager, VillagerFamilyTreeSavedData data) {
        if (uuid.equals(selectedVillager.getUUID()) && VillagerNameStateService.isTracked(selectedVillager)) {
            return VillagerGenderService.ensureAssigned(selectedVillager);
        }

        VillagerFamilyTreeSavedData.NodeData node = data.nodes().get(uuid);
        return node == null ? VillagerGenderService.GENDER_UNKNOWN : node.genderId;
    }

    private static boolean matchesSnapshot(VillagerFamilyTreeSavedData.NodeData node, VillagerStatsService.StatSnapshot stats) {
        return node.generosity == stats.generosity()
                && node.timeliness == stats.timeliness()
                && node.intellect == stats.intellect()
                && node.hoarder == stats.hoarder()
                && node.vitality == stats.vitality()
                && node.agility == stats.agility()
                && node.strength == stats.strength()
                && node.armor == stats.armor()
                && node.motivation == stats.motivation()
                && node.efficiency == stats.efficiency()
                && node.plantWhisperer == stats.plantWhisperer()
                && node.ranger == stats.ranger();
    }

    private static void applySnapshot(VillagerFamilyTreeSavedData.NodeData node, VillagerStatsService.StatSnapshot stats) {
        node.generosity = stats.generosity();
        node.timeliness = stats.timeliness();
        node.intellect = stats.intellect();
        node.hoarder = stats.hoarder();
        node.vitality = stats.vitality();
        node.agility = stats.agility();
        node.strength = stats.strength();
        node.armor = stats.armor();
        node.motivation = stats.motivation();
        node.efficiency = stats.efficiency();
        node.plantWhisperer = stats.plantWhisperer();
        node.ranger = stats.ranger();
    }

    private record NameParts(String firstName, String lastName) {
    }

    private record NodeStats(
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
            int ranger
    ) {
        static NodeStats from(VillagerStatsService.StatSnapshot snapshot) {
            return new NodeStats(
                    snapshot.generosity(),
                    snapshot.timeliness(),
                    snapshot.intellect(),
                    snapshot.hoarder(),
                    snapshot.vitality(),
                    snapshot.agility(),
                    snapshot.strength(),
                    snapshot.armor(),
                    snapshot.motivation(),
                    snapshot.efficiency(),
                    snapshot.plantWhisperer(),
                    snapshot.ranger()
            );
        }

        static NodeStats from(VillagerFamilyTreeSavedData.NodeData node) {
            return new NodeStats(
                    node.generosity,
                    node.timeliness,
                    node.intellect,
                    node.hoarder,
                    node.vitality,
                    node.agility,
                    node.strength,
                    node.armor,
                    node.motivation,
                    node.efficiency,
                    node.plantWhisperer,
                    node.ranger
            );
        }

        static NodeStats empty() {
            return new NodeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
