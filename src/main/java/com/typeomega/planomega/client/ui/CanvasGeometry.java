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
import java.util.HashMap;
import java.util.HashSet;
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
        return findEdgeAt(graph, wx, wy, false);
    }

    public static @Nullable PlanEdge findEdgeAt(PlanGraph graph, double wx, double wy, boolean orthogonal) {
        double maxD2 = EDGE_HIT_DISTANCE * EDGE_HIT_DISTANCE;
        PlanEdge closest = null;
        for (PlanEdge edge : graph.edges().values()) {
            Optional<EdgeEndpoints> ptsOpt = resolveEdgeEndpoints(graph, edge);
            if (ptsOpt.isEmpty()) {
                continue;
            }
            EdgeEndpoints pts = ptsOpt.get();
            double d2 = orthogonal
                    ? distanceSqToPolyline(wx, wy, sampleOrthogonal(pts.start(), pts.end()))
                    : distanceSqToBezier(wx, wy, pts.start(), pts.end(), 18);
            if (d2 <= maxD2) {
                maxD2 = d2;
                closest = edge;
            }
        }
        return closest;
    }

    public static List<Vec2> sampleOrthogonal(Vec2 start, Vec2 end) {
        List<Vec2> pts = new ArrayList<>(6);
        pts.add(start);
        if (end.x() >= start.x() + 24.0) {
            double midX = (start.x() + end.x()) * 0.5;
            pts.add(new Vec2(midX, start.y()));
            pts.add(new Vec2(midX, end.y()));
        } else {
            double stub = 22.0;
            double midY = Math.abs(start.y() - end.y()) < 24.0
                    ? Math.min(start.y(), end.y()) - 48.0
                    : (start.y() + end.y()) * 0.5;
            pts.add(new Vec2(start.x() + stub, start.y()));
            pts.add(new Vec2(start.x() + stub, midY));
            pts.add(new Vec2(end.x() - stub, midY));
            pts.add(new Vec2(end.x() - stub, end.y()));
        }
        pts.add(end);
        return pts;
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
        return distanceSqToPolyline(px, py, sampleBezier(start, end, segments));
    }

    private static double distanceSqToPolyline(double px, double py, List<Vec2> pts) {
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
        return CanvasLayoutHelper.autoLayoutLeftToRight(graph, snapToGrid);
    }

    public static FlowHighlight computeFlowHighlight(
            PlanGraph graph,
            @Nullable String hoveredNodeId,
            @Nullable String hoveredEdgeId,
            @Nullable PortHit hoveredPort
    ) {
        if (hoveredNodeId == null && hoveredEdgeId == null && hoveredPort == null) {
            return FlowHighlight.EMPTY;
        }
        Set<String> nodeIds = new HashSet<>();
        Set<String> edgeIds = new HashSet<>();

        Map<String, List<PlanEdge>> outEdges = new HashMap<>();
        Map<String, List<PlanEdge>> inEdges = new HashMap<>();
        for (PlanEdge edge : graph.edges().values()) {
            outEdges.computeIfAbsent(edge.fromNodeId(), k -> new ArrayList<>()).add(edge);
            inEdges.computeIfAbsent(edge.toNodeId(), k -> new ArrayList<>()).add(edge);
        }

        Queue<String> upQueue = new ArrayDeque<>();
        Queue<String> downQueue = new ArrayDeque<>();

        if (hoveredEdgeId != null) {
            PlanEdge edge = graph.edges().get(hoveredEdgeId);
            if (edge != null) {
                edgeIds.add(edge.id());
                if (nodeIds.add(edge.fromNodeId())) {
                    upQueue.add(edge.fromNodeId());
                }
                if (nodeIds.add(edge.toNodeId())) {
                    downQueue.add(edge.toNodeId());
                }
            }
        } else if (hoveredPort != null) {
            String hostId = hoveredPort.nodeId();
            nodeIds.add(hostId);
            if (hoveredPort.port().direction() == PortDirection.INPUT) {
                for (PlanEdge e : inEdges.getOrDefault(hostId, List.of())) {
                    if (e.toPortId().equals(hoveredPort.port().id())) {
                        edgeIds.add(e.id());
                        if (nodeIds.add(e.fromNodeId())) {
                            upQueue.add(e.fromNodeId());
                        }
                    }
                }
            } else {
                for (PlanEdge e : outEdges.getOrDefault(hostId, List.of())) {
                    if (e.fromPortId().equals(hoveredPort.port().id())) {
                        edgeIds.add(e.id());
                        if (nodeIds.add(e.toNodeId())) {
                            downQueue.add(e.toNodeId());
                        }
                    }
                }
            }
        } else if (hoveredNodeId != null) {
            PlanNode hovered = graph.nodes().get(hoveredNodeId);
            if (hovered instanceof GroupNode group) {
                for (String childId : group.childNodeIds()) {
                    if (nodeIds.add(childId)) {
                        upQueue.add(childId);
                        downQueue.add(childId);
                    }
                }
                nodeIds.add(group.id());
            } else {
                nodeIds.add(hoveredNodeId);
                upQueue.add(hoveredNodeId);
                downQueue.add(hoveredNodeId);
            }
        }

        Set<String> visitedUp = new HashSet<>(upQueue);
        while (!upQueue.isEmpty()) {
            String cur = upQueue.poll();
            for (PlanEdge e : inEdges.getOrDefault(cur, List.of())) {
                edgeIds.add(e.id());
                nodeIds.add(e.fromNodeId());
                if (visitedUp.add(e.fromNodeId())) {
                    upQueue.add(e.fromNodeId());
                }
            }
        }

        Set<String> visitedDown = new HashSet<>(downQueue);
        while (!downQueue.isEmpty()) {
            String cur = downQueue.poll();
            for (PlanEdge e : outEdges.getOrDefault(cur, List.of())) {
                edgeIds.add(e.id());
                nodeIds.add(e.toNodeId());
                if (visitedDown.add(e.toNodeId())) {
                    downQueue.add(e.toNodeId());
                }
            }
        }

        return new FlowHighlight(nodeIds, edgeIds);
    }

    public record EdgeEndpoints(Vec2 start, Vec2 end) {
    }

    public record FlowHighlight(Set<String> nodeIds, Set<String> edgeIds) {
        public static final FlowHighlight EMPTY = new FlowHighlight(Set.of(), Set.of());

        public boolean isActive() {
            return !nodeIds.isEmpty() || !edgeIds.isEmpty();
        }
    }
}
