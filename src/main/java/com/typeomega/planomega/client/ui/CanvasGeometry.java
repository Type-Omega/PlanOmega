package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.audit.FrictionFlag;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourcePort;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Pure geometry and hit-testing helpers for nodes, ports, collapsed groups, and cubic Bézier edges.
 */
public final class CanvasGeometry {

    public static final double PORT_HIT_RADIUS = 8.5;
    public static final double EDGE_HIT_DISTANCE = 7.0;

    private CanvasGeometry() {
    }

    public record Vec2(double x, double y) {
    }

    public record PortHit(String nodeId, ResourcePort port, Vec2 worldPos) {
    }

    public static double visualWidth(PlanNode node) {
        if (node instanceof GroupNode g && g.collapsed()) {
            return GroupNode.COLLAPSED_WIDTH;
        }
        return node.width();
    }

    public static double visualHeight(PlanNode node) {
        if (node instanceof GroupNode g && g.collapsed()) {
            return GroupNode.COLLAPSED_HEIGHT;
        }
        return node.height();
    }

    public static boolean containsPoint(PlanNode node, double wx, double wy) {
        double w = visualWidth(node);
        double h = visualHeight(node);
        return wx >= node.x() && wx <= node.x() + w && wy >= node.y() && wy <= node.y() + h;
    }

    public static boolean intersectsRect(PlanNode node, double rx1, double ry1, double rx2, double ry2) {
        double nx1 = node.x();
        double ny1 = node.y();
        double nx2 = nx1 + visualWidth(node);
        double ny2 = ny1 + visualHeight(node);
        return nx1 <= rx2 && nx2 >= rx1 && ny1 <= ry2 && ny2 >= ry1;
    }

    public static Vec2 portWorldPos(ProcessNode node, ResourcePort port) {
        List<ResourcePort> list = port.direction() == PortDirection.INPUT ? node.inputs() : node.outputs();
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(port.id())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        double px = port.direction() == PortDirection.INPUT ? node.x() : node.x() + node.width();
        double py = node.y() + ProcessNode.HEADER_HEIGHT + ProcessNode.META_HEIGHT
                + (idx + 0.5) * ProcessNode.PORT_ROW_HEIGHT;
        return new Vec2(px, py);
    }

    public static @Nullable PortHit findPortAt(PlanGraph graph, double wx, double wy) {
        double bestDistSq = PORT_HIT_RADIUS * PORT_HIT_RADIUS;
        PortHit best = null;

        for (PlanNode node : graph.nodes().values()) {
            if (!(node instanceof ProcessNode proc) || graph.isHiddenByCollapsedGroup(node.id())) {
                continue;
            }
            for (ResourcePort port : proc.inputs()) {
                Vec2 pos = portWorldPos(proc, port);
                double d2 = distSq(wx, wy, pos.x(), pos.y());
                if (d2 <= bestDistSq) {
                    bestDistSq = d2;
                    best = new PortHit(node.id(), port, pos);
                }
            }
            for (ResourcePort port : proc.outputs()) {
                Vec2 pos = portWorldPos(proc, port);
                double d2 = distSq(wx, wy, pos.x(), pos.y());
                if (d2 <= bestDistSq) {
                    bestDistSq = d2;
                    best = new PortHit(node.id(), port, pos);
                }
            }
        }
        return best;
    }

    /**
     * Finds the topmost visible node under (wx, wy), checking foreground cards before background groups.
     */
    public static @Nullable PlanNode findNodeAt(PlanGraph graph, double wx, double wy) {
        List<PlanNode> ordered = new ArrayList<>(graph.nodes().values());
        // Foreground nodes first (reverse insertion order)
        for (int i = ordered.size() - 1; i >= 0; i--) {
            PlanNode node = ordered.get(i);
            if (node instanceof GroupNode) {
                continue;
            }
            if (!graph.isHiddenByCollapsedGroup(node.id()) && containsPoint(node, wx, wy)) {
                return node;
            }
        }
        // Then groups (reverse insertion order)
        for (int i = ordered.size() - 1; i >= 0; i--) {
            PlanNode node = ordered.get(i);
            if (node instanceof GroupNode && !graph.isHiddenByCollapsedGroup(node.id()) && containsPoint(node, wx, wy)) {
                return node;
            }
        }
        return null;
    }

    public static Optional<EdgeEndpoints> resolveEdgeEndpoints(PlanGraph graph, PlanEdge edge) {
        String fromHostId = graph.resolveVisibleHostId(edge.fromNodeId());
        String toHostId = graph.resolveVisibleHostId(edge.toNodeId());
        if (fromHostId == null || toHostId == null || fromHostId.equals(toHostId)) {
            return Optional.empty();
        }
        Vec2 start = resolveHostPort(graph, edge.fromNodeId(), edge.fromPortId(), fromHostId, true);
        Vec2 end = resolveHostPort(graph, edge.toNodeId(), edge.toPortId(), toHostId, false);
        if (start == null || end == null) {
            return Optional.empty();
        }
        return Optional.of(new EdgeEndpoints(start, end));
    }

    private static @Nullable Vec2 resolveHostPort(
            PlanGraph graph,
            String actualNodeId,
            String portId,
            String visibleHostId,
            boolean isOutput
    ) {
        PlanNode host = graph.nodes().get(visibleHostId);
        if (host instanceof GroupNode group && group.collapsed()) {
            double x = isOutput ? group.x() + GroupNode.COLLAPSED_WIDTH : group.x();
            double y = group.y() + GroupNode.COLLAPSED_HEIGHT * 0.65;
            return new Vec2(x, y);
        }
        PlanNode actual = graph.nodes().get(actualNodeId);
        if (actual instanceof ProcessNode proc) {
            Optional<ResourcePort> port = proc.findPort(portId);
            if (port.isPresent()) {
                return portWorldPos(proc, port.get());
            }
        }
        return null;
    }

    public static @Nullable PlanEdge findEdgeAt(PlanGraph graph, double wx, double wy) {
        double maxD2 = EDGE_HIT_DISTANCE * EDGE_HIT_DISTANCE;
        PlanEdge closest = null;
        for (PlanEdge edge : graph.edges().values()) {
            Optional<EdgeEndpoints> ptsOpt = resolveEdgeEndpoints(graph, edge);
            if (ptsOpt.isEmpty()) {
                continue;
            }
            EdgeEndpoints pts = ptsOpt.get();
            double d2 = distanceSqToBezier(wx, wy, pts.start(), pts.end(), 18);
            if (d2 <= maxD2) {
                maxD2 = d2;
                closest = edge;
            }
        }
        return closest;
    }

    public static List<Vec2> sampleBezier(Vec2 start, Vec2 end, int segments) {
        double dx = Math.abs(end.x() - start.x());
        double handle = Math.max(40.0, Math.min(180.0, dx * 0.5));
        double c1x = start.x() + handle;
        double c1y = start.y();
        double c2x = end.x() - handle;
        double c2y = end.y();

        List<Vec2> points = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double u = 1.0 - t;
            double x = u * u * u * start.x()
                    + 3 * u * u * t * c1x
                    + 3 * u * t * t * c2x
                    + t * t * t * end.x();
            double y = u * u * u * start.y()
                    + 3 * u * u * t * c1y
                    + 3 * u * t * t * c2y
                    + t * t * t * end.y();
            points.add(new Vec2(x, y));
        }
        return points;
    }

    private static double distanceSqToBezier(double px, double py, Vec2 start, Vec2 end, int segments) {
        List<Vec2> pts = sampleBezier(start, end, segments);
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pts.size() - 1; i++) {
            best = Math.min(best, distSqPointToSegment(px, py, pts.get(i), pts.get(i + 1)));
        }
        return best;
    }

    private static double distSqPointToSegment(double px, double py, Vec2 a, Vec2 b) {
        double vx = b.x() - a.x();
        double vy = b.y() - a.y();
        double len2 = vx * vx + vy * vy;
        if (len2 <= 1e-9) {
            return distSq(px, py, a.x(), a.y());
        }
        double t = Math.max(0.0, Math.min(1.0, ((px - a.x()) * vx + (py - a.y()) * vy) / len2));
        double projX = a.x() + t * vx;
        double projY = a.y() + t * vy;
        return distSq(px, py, projX, projY);
    }

    private static double distSq(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    public static @Nullable ProcessNode findAuditBadgeNodeAt(
            PlanGraph graph,
            @Nullable AuditReport audit,
            Font font,
            double wx,
            double wy
    ) {
        if (audit == null || audit.flags().isEmpty()) {
            return null;
        }
        List<PlanNode> ordered = new ArrayList<>(graph.nodes().values());
        for (int i = ordered.size() - 1; i >= 0; i--) {
            PlanNode node = ordered.get(i);
            if (!(node instanceof ProcessNode proc) || graph.isHiddenByCollapsedGroup(node.id())) {
                continue;
            }
            List<FrictionFlag> flags = audit.forNode(proc.id());
            if (flags.isEmpty()) {
                continue;
            }
            String tag = "!" + flags.size();
            int tw = font.width(tag) + 15;
            double fx = proc.x() - 2;
            double fy = proc.y() - 10;
            if (wx >= fx && wx <= fx + tw && wy >= fy && wy <= fy + 10) {
                return proc;
            }
        }
        return null;
    }

    /**
     * Arranges nodes topologically left-to-right (layered Sugiyama-style layout) while keeping
     * groups intact. Internal nodes within each group are arranged first, then top-level nodes
     * and groups are arranged in columns.
     */
    public static PlanGraph autoLayoutLeftToRight(PlanGraph graph, boolean snapToGrid) {
        if (graph.nodes().isEmpty()) {
            return graph;
        }

        Map<String, PlanNode> working = new LinkedHashMap<>(graph.nodes());

        // 1. Arrange children inside each GroupNode first (if expanded & non-empty)
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof GroupNode group && !group.childNodeIds().isEmpty()) {
                Set<String> childSet = new LinkedHashSet<>(group.childNodeIds());
                List<String> validChildren = childSet.stream().filter(working::containsKey).toList();
                if (validChildren.size() > 1) {
                    layoutSubset(validChildren, working, graph.edges().values(), 70.0, 26.0, snapToGrid, false);
                }
                if (!group.collapsed()) {
                    working.put(group.id(), recomputeGroupBoundsLocal(group, working));
                }
            }
        }

        // 2. Collect top-level units (all GroupNodes + all ungrouped nodes)
        List<String> topUnits = new ArrayList<>();
        for (PlanNode node : working.values()) {
            if (node instanceof GroupNode || node.groupId() == null) {
                topUnits.add(node.id());
            }
        }

        // Map any node ID to its top-level unit ID
        Map<String, String> toTopUnit = new HashMap<>();
        for (PlanNode node : working.values()) {
            String top = node.id();
            Set<String> seen = new HashSet<>();
            PlanNode cur = node;
            while (cur != null && cur.groupId() != null && seen.add(cur.groupId())) {
                top = cur.groupId();
                cur = working.get(top);
            }
            toTopUnit.put(node.id(), top);
        }

        // Build top-level edges
        List<PlanEdge> topEdges = new ArrayList<>();
        for (PlanEdge e : graph.edges().values()) {
            String fromTop = toTopUnit.get(e.fromNodeId());
            String toTop = toTopUnit.get(e.toNodeId());
            if (fromTop != null && toTop != null && !fromTop.equals(toTop)) {
                topEdges.add(new PlanEdge(e.id(), fromTop, e.fromPortId(), toTop, e.toPortId(), e.resource(), e.manualLock()));
            }
        }

        layoutSubset(topUnits, working, topEdges, 90.0, 32.0, snapToGrid, true);

        return new PlanGraph(
                graph.schemaVersion(),
                graph.id(),
                graph.name(),
                graph.author(),
                graph.description(),
                working,
                graph.edges()
        );
    }

    private static void layoutSubset(
            List<String> unitIds,
            Map<String, PlanNode> working,
            java.util.Collection<PlanEdge> edges,
            double gapX,
            double gapY,
            boolean snapToGrid,
            boolean moveGroupChildren
    ) {
        if (unitIds.isEmpty()) {
            return;
        }
        Set<String> unitSet = new LinkedHashSet<>(unitIds);
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Map<String, List<String>> revAdj = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String id : unitSet) {
            adj.put(id, new ArrayList<>());
            revAdj.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }

        Set<String> seenEdgePairs = new HashSet<>();
        for (PlanEdge edge : edges) {
            if (!unitSet.contains(edge.fromNodeId()) || !unitSet.contains(edge.toNodeId())) {
                continue;
            }
            if (edge.fromNodeId().equals(edge.toNodeId())) {
                continue;
            }
            String pair = edge.fromNodeId() + "->" + edge.toNodeId();
            if (seenEdgePairs.add(pair)) {
                adj.get(edge.fromNodeId()).add(edge.toNodeId());
                revAdj.get(edge.toNodeId()).add(edge.fromNodeId());
                inDegree.put(edge.toNodeId(), inDegree.get(edge.toNodeId()) + 1);
            }
        }

        // Longest-path topological layering with cycle breaking
        Map<String, Integer> layer = new LinkedHashMap<>();
        Queue<String> ready = new ArrayDeque<>();
        for (String id : unitSet) {
            if (inDegree.get(id) == 0) {
                ready.add(id);
                layer.put(id, 0);
            }
        }

        Set<String> placed = new HashSet<>();
        while (placed.size() < unitSet.size()) {
            if (ready.isEmpty()) {
                // Cycle fallback: pick unplaced node with smallest remaining in-degree
                String pick = null;
                int bestDeg = Integer.MAX_VALUE;
                for (String id : unitSet) {
                    if (!placed.contains(id) && inDegree.get(id) < bestDeg) {
                        bestDeg = inDegree.get(id);
                        pick = id;
                    }
                }
                if (pick == null) {
                    break;
                }
                int maxPredLayer = -1;
                for (String pred : revAdj.get(pick)) {
                    if (placed.contains(pred)) {
                        maxPredLayer = Math.max(maxPredLayer, layer.getOrDefault(pred, 0));
                    }
                }
                layer.put(pick, maxPredLayer + 1);
                ready.add(pick);
            }

            String u = ready.poll();
            if (!placed.add(u)) {
                continue;
            }
            int uLayer = layer.getOrDefault(u, 0);
            for (String v : adj.get(u)) {
                if (!placed.contains(v)) {
                    layer.put(v, Math.max(layer.getOrDefault(v, 0), uLayer + 1));
                    int deg = inDegree.get(v) - 1;
                    inDegree.put(v, deg);
                    if (deg <= 0) {
                        ready.add(v);
                    }
                }
            }
        }

        int maxLayer = 0;
        for (int l : layer.values()) {
            maxLayer = Math.max(maxLayer, l);
        }

        List<List<String>> columns = new ArrayList<>(maxLayer + 1);
        for (int i = 0; i <= maxLayer; i++) {
            columns.add(new ArrayList<>());
        }
        for (String id : unitSet) {
            columns.get(layer.getOrDefault(id, 0)).add(id);
        }

        // Barycenter ordering per column to minimize crossings
        Map<String, Double> tempY = new HashMap<>();
        for (String id : unitSet) {
            tempY.put(id, working.get(id).y());
        }

        double curX = 0.0;
        for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
            List<String> col = columns.get(colIdx);
            if (col.isEmpty()) {
                continue;
            }
            if (colIdx > 0) {
                final int currentCol = colIdx;
                col.sort(Comparator.comparingDouble(id -> {
                    List<String> preds = revAdj.get(id);
                    if (preds.isEmpty()) {
                        return tempY.getOrDefault(id, 0.0);
                    }
                    double sum = 0.0;
                    int count = 0;
                    for (String p : preds) {
                        if (layer.getOrDefault(p, 0) < currentCol) {
                            sum += tempY.getOrDefault(p, 0.0);
                            count++;
                        }
                    }
                    return count > 0 ? sum / count : tempY.getOrDefault(id, 0.0);
                }));
            } else {
                col.sort(Comparator.comparingDouble(id -> working.get(id).y()));
            }

            double totalH = 0.0;
            double maxW = 0.0;
            for (int i = 0; i < col.size(); i++) {
                PlanNode n = working.get(col.get(i));
                totalH += visualHeight(n);
                if (i > 0) {
                    totalH += gapY;
                }
                maxW = Math.max(maxW, visualWidth(n));
            }

            double curY = -totalH * 0.5;
            for (String id : col) {
                PlanNode n = working.get(id);
                double nx = CanvasViewport.snap(curX, snapToGrid);
                double ny = CanvasViewport.snap(curY, snapToGrid);
                double dx = nx - n.x();
                double dy = ny - n.y();
                working.put(id, n.withPosition(nx, ny));
                tempY.put(id, ny);

                if (moveGroupChildren && n instanceof GroupNode group) {
                    shiftGroupDescendants(group, dx, dy, working);
                }

                curY += visualHeight(n) + gapY;
            }
            curX += maxW + gapX;
        }
    }

    private static void shiftGroupDescendants(GroupNode group, double dx, double dy, Map<String, PlanNode> working) {
        if (dx == 0.0 && dy == 0.0) {
            return;
        }
        for (String childId : group.childNodeIds()) {
            PlanNode child = working.get(childId);
            if (child != null) {
                working.put(childId, child.withPosition(child.x() + dx, child.y() + dy));
                if (child instanceof GroupNode nested) {
                    shiftGroupDescendants(nested, dx, dy, working);
                }
            }
        }
    }

    private static GroupNode recomputeGroupBoundsLocal(GroupNode group, Map<String, PlanNode> working) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (String childId : group.childNodeIds()) {
            PlanNode child = working.get(childId);
            if (child == null) {
                continue;
            }
            minX = Math.min(minX, child.x());
            minY = Math.min(minY, child.y());
            maxX = Math.max(maxX, child.x() + child.width());
            maxY = Math.max(maxY, child.y() + child.height());
        }
        if (!Double.isFinite(minX)) {
            return group;
        }
        return new GroupNode(
                group.id(),
                minX - 18.0,
                minY - 34.0,
                (maxX - minX) + 36.0,
                (maxY - minY) + 50.0,
                group.groupId(),
                group.title(),
                group.color(),
                group.collapsed(),
                group.childNodeIds()
        );
    }

    public record EdgeEndpoints(Vec2 start, Vec2 end) {
    }
}
