package org.z2six.villageroverhaul.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.z2six.villageroverhaul.network.familytree.PacketVillagerFamilyTreeData;

final class FamilyTreeGraphRenderer {
    static final int NODE_W = 96;
    static final int NODE_H = 30;
    private static final int EDGE_COLOR = 0xFF8A8A8A;
    private static final int EDGE_HIGHLIGHT_COLOR = 0xFFF0C75E;
    private static final int NODE_BG = 0xFF181818;
    private static final int NODE_BG_SELECTED = 0xFF23301C;
    private static final int NODE_BORDER = 0xFF545454;
    private static final int NODE_BORDER_SELECTED = 0xFF86D56B;
    private static final int NODE_BORDER_HOVER = 0xFFF0C75E;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int SURNAME_COLOR = 0xFFC8C8C8;

    private FamilyTreeGraphRenderer() {
    }

    static @Nullable List<Component> render(
            GuiGraphics gg,
            Font font,
            @Nullable PacketVillagerFamilyTreeData data,
            int viewportX,
            int viewportY,
            int viewportW,
            int viewportH,
            float zoom,
            double panX,
            double panY,
            int mouseX,
            int mouseY
    ) {
        gg.fill(viewportX, viewportY, viewportX + viewportW, viewportY + viewportH, 0xFF101010);
        gg.fill(viewportX, viewportY, viewportX + viewportW, viewportY + 1, 0xFF2E2E2E);
        gg.fill(viewportX, viewportY + viewportH - 1, viewportX + viewportW, viewportY + viewportH, 0xFF2E2E2E);
        gg.fill(viewportX, viewportY, viewportX + 1, viewportY + viewportH, 0xFF2E2E2E);
        gg.fill(viewportX + viewportW - 1, viewportY, viewportX + viewportW, viewportY + viewportH, 0xFF2E2E2E);

        if (data == null) {
            gg.drawString(font, Component.literal("Syncing family tree..."), viewportX + 8, viewportY + 8, 0xFFAAAAAA, false);
            return null;
        }
        if (!data.ok()) {
            gg.drawString(font, Component.literal("No VillagerOverhaul family data available."), viewportX + 8, viewportY + 8, 0xFFAAAAAA, false);
            return null;
        }
        if (data.nodes() == null || data.nodes().isEmpty()) {
            gg.drawString(font, Component.literal("No recorded family links yet."), viewportX + 8, viewportY + 8, 0xFFAAAAAA, false);
            return null;
        }

        double originX = viewportX + viewportW / 2.0D + panX;
        double originY = viewportY + viewportH / 2.0D + panY;
        float clampedZoom = Mth.clamp(zoom, 0.4F, 3.0F);

        Map<LongPair, PacketVillagerFamilyTreeData.Node> nodesById = new HashMap<>();
        for (PacketVillagerFamilyTreeData.Node node : data.nodes()) {
            nodesById.put(new LongPair(node.uuidMsb(), node.uuidLsb()), node);
        }

        LongPair hoveredId = hoveredNode(data, originX, originY, clampedZoom, mouseX, mouseY);
        RelationKey hoveredRelationKey = null;
        Set<LongPair> highlightedParentIds = new HashSet<>();
        if (hoveredId != null) {
            PacketVillagerFamilyTreeData.Relation directParents = findParentsForChild(data, hoveredId);
            if (directParents != null) {
                hoveredRelationKey = RelationKey.from(directParents);
                highlightedParentIds.add(new LongPair(directParents.parentAMsb(), directParents.parentALsb()));
                highlightedParentIds.add(new LongPair(directParents.parentBMsb(), directParents.parentBLsb()));
            }
        }

        Map<RelationKey, FamilyBranch> branches = buildBranches(data, nodesById);

        boolean scissor = false;
        try {
            gg.enableScissor(viewportX + 1, viewportY + 1, viewportX + viewportW - 1, viewportY + viewportH - 1);
            scissor = true;
        } catch (Throwable ignored) {}

        try {
            gg.pose().pushPose();
            gg.pose().translate(originX, originY, 0.0F);
            gg.pose().scale(clampedZoom, clampedZoom, 1.0F);

            FamilyBranch highlightedBranch = null;
            for (Map.Entry<RelationKey, FamilyBranch> entry : branches.entrySet()) {
                if (entry.getKey().equals(hoveredRelationKey)) {
                    highlightedBranch = entry.getValue();
                    continue;
                }
                drawFamilyBranch(gg, entry.getValue(), EDGE_COLOR, 1);
            }
            if (highlightedBranch != null) {
                drawFamilyBranch(gg, highlightedBranch, EDGE_HIGHLIGHT_COLOR, 3);
            }

            for (PacketVillagerFamilyTreeData.Node node : data.nodes()) {
                int bg = node.selected() ? NODE_BG_SELECTED : NODE_BG;
                LongPair nodeId = new LongPair(node.uuidMsb(), node.uuidLsb());
                boolean hovered = nodeId.equals(hoveredId);
                boolean highlightedParent = highlightedParentIds.contains(nodeId);
                int border = node.selected()
                        ? NODE_BORDER_SELECTED
                        : ((hovered || highlightedParent) ? NODE_BORDER_HOVER : NODE_BORDER);
                gg.fill(node.x(), node.y(), node.x() + NODE_W, node.y() + NODE_H, bg);
                gg.fill(node.x(), node.y(), node.x() + NODE_W, node.y() + 1, border);
                gg.fill(node.x(), node.y() + NODE_H - 1, node.x() + NODE_W, node.y() + NODE_H, border);
                gg.fill(node.x(), node.y(), node.x() + 1, node.y() + NODE_H, border);
                gg.fill(node.x() + NODE_W - 1, node.y(), node.x() + NODE_W, node.y() + NODE_H, border);

                String first = node.firstName() == null ? "" : node.firstName();
                String last = node.lastName() == null ? "" : node.lastName();
                int firstX = node.x() + 5;
                int textY = node.y() + 5;
                gg.drawString(font, first, firstX, textY, NAME_COLOR, false);
                gg.drawString(font, last, firstX, textY + font.lineHeight + 1, SURNAME_COLOR, false);
            }

            gg.pose().popPose();
        } finally {
            if (scissor) {
                try {
                    gg.disableScissor();
                } catch (Throwable ignored) {}
            }
        }
        PacketVillagerFamilyTreeData.Node hovered = hoveredId == null ? null : nodesById.get(hoveredId);
        if (hovered == null) {
            return null;
        }
        return buildTooltip(hovered);
    }

    static Bounds computeBounds(@Nullable PacketVillagerFamilyTreeData data) {
        if (data == null || data.nodes() == null || data.nodes().isEmpty()) {
            return new Bounds(-NODE_W / 2, -NODE_H / 2, NODE_W, NODE_H);
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PacketVillagerFamilyTreeData.Node node : data.nodes()) {
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x() + NODE_W);
            maxY = Math.max(maxY, node.y() + NODE_H);
        }
        return new Bounds(minX, minY, maxX, maxY);
    }

    private static @Nullable LongPair hoveredNode(
            PacketVillagerFamilyTreeData data,
            double originX,
            double originY,
            float zoom,
            int mouseX,
            int mouseY
    ) {
        double worldX = (mouseX - originX) / zoom;
        double worldY = (mouseY - originY) / zoom;
        for (PacketVillagerFamilyTreeData.Node node : data.nodes()) {
            if (worldX >= node.x() && worldX <= node.x() + NODE_W && worldY >= node.y() && worldY <= node.y() + NODE_H) {
                return new LongPair(node.uuidMsb(), node.uuidLsb());
            }
        }
        return null;
    }

    private static @Nullable PacketVillagerFamilyTreeData.Relation findParentsForChild(
            PacketVillagerFamilyTreeData data,
            LongPair childId
    ) {
        for (PacketVillagerFamilyTreeData.Relation relation : data.relations()) {
            if (relation.childMsb() == childId.msb() && relation.childLsb() == childId.lsb()) {
                return relation;
            }
        }
        return null;
    }

    private static Map<RelationKey, FamilyBranch> buildBranches(
            PacketVillagerFamilyTreeData data,
            Map<LongPair, PacketVillagerFamilyTreeData.Node> nodesById
    ) {
        Map<RelationKey, FamilyBranch> branches = new HashMap<>();
        for (PacketVillagerFamilyTreeData.Relation relation : data.relations()) {
            PacketVillagerFamilyTreeData.Node child = nodesById.get(new LongPair(relation.childMsb(), relation.childLsb()));
            PacketVillagerFamilyTreeData.Node parentA = nodesById.get(new LongPair(relation.parentAMsb(), relation.parentALsb()));
            PacketVillagerFamilyTreeData.Node parentB = nodesById.get(new LongPair(relation.parentBMsb(), relation.parentBLsb()));
            if (child == null || parentA == null || parentB == null) {
                continue;
            }

            RelationKey key = RelationKey.from(relation);
            FamilyBranch branch = branches.computeIfAbsent(key, ignored -> new FamilyBranch(parentA, parentB));
            branch.children.add(child);
        }

        for (FamilyBranch branch : branches.values()) {
            branch.children.sort((left, right) -> Integer.compare(left.x(), right.x()));
        }
        return branches;
    }

    private static void drawFamilyBranch(GuiGraphics gg, FamilyBranch branch, int color, int thickness) {
        PacketVillagerFamilyTreeData.Node parentA = branch.parentA;
        PacketVillagerFamilyTreeData.Node parentB = branch.parentB;
        if (branch.children.isEmpty()) {
            return;
        }

        int parentACenterX = parentA.x() + NODE_W / 2;
        int parentBCenterX = parentB.x() + NODE_W / 2;
        int parentBottomY = Math.max(parentA.y(), parentB.y()) + NODE_H;
        int parentJunctionY = parentBottomY + 10;
        int parentMidX = (parentACenterX + parentBCenterX) / 2;

        int minChildCenterX = Integer.MAX_VALUE;
        int maxChildCenterX = Integer.MIN_VALUE;
        int childTopY = Integer.MAX_VALUE;
        for (PacketVillagerFamilyTreeData.Node child : branch.children) {
            int childCenterX = child.x() + NODE_W / 2;
            minChildCenterX = Math.min(minChildCenterX, childCenterX);
            maxChildCenterX = Math.max(maxChildCenterX, childCenterX);
            childTopY = Math.min(childTopY, child.y());
        }
        int childBusY = Math.max(parentJunctionY + 10, childTopY - 10);

        drawVertical(gg, parentACenterX, parentA.y() + NODE_H, parentJunctionY, color, thickness);
        drawVertical(gg, parentBCenterX, parentB.y() + NODE_H, parentJunctionY, color, thickness);
        drawHorizontal(gg, Math.min(parentACenterX, parentBCenterX), Math.max(parentACenterX, parentBCenterX), parentJunctionY, color, thickness);
        drawVertical(gg, parentMidX, parentJunctionY, childBusY, color, thickness);
        drawHorizontal(gg, Math.min(minChildCenterX, parentMidX), Math.max(maxChildCenterX, parentMidX), childBusY, color, thickness);

        for (PacketVillagerFamilyTreeData.Node child : branch.children) {
            int childCenterX = child.x() + NODE_W / 2;
            drawVertical(gg, childCenterX, childBusY, child.y(), color, thickness);
        }
    }

    private static void drawVertical(GuiGraphics gg, int x, int y1, int y2, int color, int thickness) {
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        int half = Math.max(0, thickness - 1) / 2;
        int width = Math.max(1, thickness);
        gg.fill(x - half, top, x - half + width, bottom + 1, color);
    }

    private static void drawHorizontal(GuiGraphics gg, int x1, int x2, int y, int color, int thickness) {
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int half = Math.max(0, thickness - 1) / 2;
        int height = Math.max(1, thickness);
        gg.fill(left, y - half, right + 1, y - half + height, color);
    }

    private static String fullName(PacketVillagerFamilyTreeData.Node node) {
        return ((node.firstName() == null ? "" : node.firstName()) + " " + (node.lastName() == null ? "" : node.lastName())).trim();
    }

    private static List<Component> buildTooltip(PacketVillagerFamilyTreeData.Node node) {
        List<Component> lines = new ArrayList<>(17);
        lines.add(Component.literal(fullName(node)).withStyle(ChatFormatting.WHITE));
        lines.add(Component.empty());
        lines.add(Component.literal("Merchant Stats").withStyle(ChatFormatting.GOLD));
        lines.add(statLine("Generosity", node.generosity()));
        lines.add(statLine("Timeliness", node.timeliness()));
        lines.add(statLine("Intellect", node.intellect()));
        lines.add(statLine("Hoarder", node.hoarder()));
        lines.add(Component.literal("Combat Stats").withStyle(ChatFormatting.RED));
        lines.add(statLine("Vitality", node.vitality()));
        lines.add(statLine("Agility", node.agility()));
        lines.add(statLine("Strength", node.strength()));
        lines.add(statLine("Armor", node.armor()));
        lines.add(Component.literal("Farming Stats").withStyle(ChatFormatting.GREEN));
        lines.add(statLine("Motivation", node.motivation()));
        lines.add(statLine("Efficiency", node.efficiency()));
        lines.add(statLine("Plant Whisperer", node.plantWhisperer()));
        lines.add(statLine("Ranger", node.ranger()));
        return lines;
    }

    private static Component statLine(String label, int value) {
        return Component.literal(label + ": " + value).withStyle(ChatFormatting.GRAY);
    }

    record Bounds(int minX, int minY, int maxX, int maxY) {
    }

    private record LongPair(long msb, long lsb) {
    }

    private record RelationKey(long parentAMsb, long parentALsb, long parentBMsb, long parentBLsb) {
        static RelationKey from(PacketVillagerFamilyTreeData.Relation relation) {
            if (compare(relation.parentAMsb(), relation.parentALsb(), relation.parentBMsb(), relation.parentBLsb()) <= 0) {
                return new RelationKey(
                        relation.parentAMsb(),
                        relation.parentALsb(),
                        relation.parentBMsb(),
                        relation.parentBLsb()
                );
            }
            return new RelationKey(
                    relation.parentBMsb(),
                    relation.parentBLsb(),
                    relation.parentAMsb(),
                    relation.parentALsb()
            );
        }

        private static int compare(long leftMsb, long leftLsb, long rightMsb, long rightLsb) {
            int msbCmp = Long.compare(leftMsb, rightMsb);
            if (msbCmp != 0) {
                return msbCmp;
            }
            return Long.compare(leftLsb, rightLsb);
        }
    }

    private static final class FamilyBranch {
        final PacketVillagerFamilyTreeData.Node parentA;
        final PacketVillagerFamilyTreeData.Node parentB;
        final List<PacketVillagerFamilyTreeData.Node> children = new ArrayList<>();

        FamilyBranch(PacketVillagerFamilyTreeData.Node parentA, PacketVillagerFamilyTreeData.Node parentB) {
            this.parentA = parentA;
            this.parentB = parentB;
        }
    }
}
