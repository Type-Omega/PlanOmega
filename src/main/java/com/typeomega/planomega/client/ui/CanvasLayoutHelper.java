package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Sugiyama-style hierarchical left-to-right auto-layout for {@link PlanGraph}.
 * Handles cycles/loops safely without infinite loops, minimizes edge crossings via
 * multi-pass barycenter sweeps, preserves {@link GroupNode} hierarchy, and arranges
 * ungrouped {@link StickyNoteNode}s neatly below the production flow.
 */
public final class CanvasLayoutHelper {

    private CanvasLayoutHelper() {
    }

    public static PlanGraph autoLayoutLeftToRight(PlanGraph graph, boolean snapToGrid) {
        if (graph.nodes().isEmpty()) {
            return graph;
        }

        Map<String, PlanNode> working = new LinkedHashMap<>(graph.nodes());

        // 1. Arrange children inside each GroupNode first
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof GroupNode group && !group.childNodeIds().isEmpty()) {
                Set<String> childSet = new LinkedHashSet<>(group.childNodeIds());
                List<String> validChildren = childSet.stream().filter(working::containsKey).toList();
                if (validChildren.size() > 1) {
                    layoutSubset(validChildren, working, graph.edges().values(), 70.0, 26.0, snapToGrid, false);
                }
                if (!group.collapsed()) {
                    working.put(group.id(), recomputeGroupBounds(group, working));
                }
            }
        }

        // 2. Collect top-level DAG units (GroupNodes + ungrouped non-sticky nodes) and ungrouped sticky notes
        List<String> topUnits = new ArrayList<>();
        List<String> topStickyNotes = new ArrayList<>();
        for (PlanNode node : working.values()) {
            if (node instanceof GroupNode || node.groupId() == null) {
                if (node instanceof StickyNoteNode) {
                    topStickyNotes.add(node.id());
                } else {
                    topUnits.add(node.id());
                }
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

        // 3. Place ungrouped sticky notes in a tidy row below the laid-out DAG
        if (!topStickyNotes.isEmpty()) {
            double minX = 0.0;
            double maxY = 0.0;
            boolean hasTopUnits = !topUnits.isEmpty();
            if (hasTopUnits) {
                minX = Double.POSITIVE_INFINITY;
                maxY = Double.NEGATIVE_INFINITY;
                for (String id : topUnits) {
                    PlanNode n = working.get(id);
                    if (n != null) {
                        minX = Math.min(minX, n.x());
                        maxY = Math.max(maxY, n.y() + CanvasGeometry.visualHeight(n));
                    }
                }
            }
            double noteX = CanvasViewport.snap(Double.isFinite(minX) ? minX : 0.0, snapToGrid);
            double noteY = CanvasViewport.snap(hasTopUnits ? maxY + 40.0 : 0.0, snapToGrid);
            for (String noteId : topStickyNotes) {
                PlanNode note = working.get(noteId);
                if (note != null) {
                    working.put(noteId, note.withPosition(noteX, noteY));
                    noteX = CanvasViewport.snap(noteX + note.width() + 24.0, snapToGrid);
                }
            }
        }

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
            Collection<PlanEdge> edges,
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
        Map<String, List<PlanEdge>> outgoingEdges = new LinkedHashMap<>();
        Map<String, List<PlanEdge>> incomingEdges = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String id : unitSet) {
            adj.put(id, new ArrayList<>());
            revAdj.put(id, new ArrayList<>());
            outgoingEdges.put(id, new ArrayList<>());
            incomingEdges.put(id, new ArrayList<>());
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
            outgoingEdges.get(edge.fromNodeId()).add(edge);
            incomingEdges.get(edge.toNodeId()).add(edge);
            String pair = edge.fromNodeId() + "->" + edge.toNodeId();
            if (seenEdgePairs.add(pair)) {
                adj.get(edge.fromNodeId()).add(edge.toNodeId());
                revAdj.get(edge.toNodeId()).add(edge.fromNodeId());
                inDegree.put(edge.toNodeId(), inDegree.get(edge.toNodeId()) + 1);
            }
        }

        // Longest-path topological layering with cycle breaking (guaranteed O(V + E) termination)
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
                // Cycle fallback: pick unplaced node with smallest remaining in-degree, breaking ties by out-degree
                String pick = null;
                int bestDeg = Integer.MAX_VALUE;
                int bestOut = -1;
                for (String id : unitSet) {
                    if (!placed.contains(id)) {
                        int deg = inDegree.get(id);
                        int out = adj.get(id).size();
                        if (deg < bestDeg || (deg == bestDeg && out > bestOut)) {
                            bestDeg = deg;
                            bestOut = out;
                            pick = id;
                        }
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
            if (u == null || !placed.add(u)) {
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

        // Initial column ordering by current Y
        for (List<String> col : columns) {
            col.sort(Comparator.comparingDouble(id -> working.get(id).y()));
        }

        // Multi-pass barycenter sweeps (forward + backward) to minimize edge crossings
        Map<String, Double> orderScore = new HashMap<>();
        refreshOrderScores(columns, orderScore);

        for (int sweep = 0; sweep < 4; sweep++) {
            // Forward pass: order colIdx using predecessors in earlier layers
            for (int colIdx = 1; colIdx < columns.size(); colIdx++) {
                final int currentCol = colIdx;
                List<String> col = columns.get(colIdx);
                Map<String, Double> bary = new HashMap<>();
                for (String id : col) {
                    bary.put(id, computeBarycenter(id, incomingEdges.get(id), true, currentCol, layer, orderScore, working));
                }
                col.sort(Comparator.comparingDouble(id -> bary.getOrDefault(id, 0.0)));
                refreshColumnScores(col, orderScore);
            }
            // Backward pass: order colIdx using successors in later layers
            for (int colIdx = columns.size() - 2; colIdx >= 0; colIdx--) {
                final int currentCol = colIdx;
                List<String> col = columns.get(colIdx);
                Map<String, Double> bary = new HashMap<>();
                for (String id : col) {
                    bary.put(id, computeBarycenter(id, outgoingEdges.get(id), false, currentCol, layer, orderScore, working));
                }
                col.sort(Comparator.comparingDouble(id -> bary.getOrDefault(id, 0.0)));
                refreshColumnScores(col, orderScore);
            }
        }

        // Assign final (x, y) coordinates per column
        double curX = 0.0;
        for (List<String> col : columns) {
            if (col.isEmpty()) {
                continue;
            }
            double totalH = 0.0;
            double maxW = 0.0;
            for (int i = 0; i < col.size(); i++) {
                PlanNode n = working.get(col.get(i));
                totalH += CanvasGeometry.visualHeight(n);
                if (i > 0) {
                    totalH += gapY;
                }
                maxW = Math.max(maxW, CanvasGeometry.visualWidth(n));
            }

            double curY = -totalH * 0.5;
            for (String id : col) {
                PlanNode n = working.get(id);
                double nx = CanvasViewport.snap(curX, snapToGrid);
                double ny = CanvasViewport.snap(curY, snapToGrid);
                double dx = nx - n.x();
                double dy = ny - n.y();
                working.put(id, n.withPosition(nx, ny));

                if (moveGroupChildren && n instanceof GroupNode group) {
                    shiftGroupDescendants(group, dx, dy, working);
                }

                curY += CanvasGeometry.visualHeight(n) + gapY;
            }
            curX += maxW + gapX;
        }
    }

    private static void refreshOrderScores(List<List<String>> columns, Map<String, Double> orderScore) {
        for (List<String> col : columns) {
            refreshColumnScores(col, orderScore);
        }
    }

    private static void refreshColumnScores(List<String> col, Map<String, Double> orderScore) {
        for (int i = 0; i < col.size(); i++) {
            orderScore.put(col.get(i), (double) i);
        }
    }

    private static double computeBarycenter(
            String nodeId,
            List<PlanEdge> incidentEdges,
            boolean usePredecessors,
            int currentCol,
            Map<String, Integer> layer,
            Map<String, Double> orderScore,
            Map<String, PlanNode> working
    ) {
        if (incidentEdges == null || incidentEdges.isEmpty()) {
            return orderScore.getOrDefault(nodeId, 0.0);
        }
        double sum = 0.0;
        int count = 0;
        for (PlanEdge edge : incidentEdges) {
            String neighborId = usePredecessors ? edge.fromNodeId() : edge.toNodeId();
            int neighborLayer = layer.getOrDefault(neighborId, currentCol);
            boolean validDir = usePredecessors ? (neighborLayer < currentCol) : (neighborLayer > currentCol);
            if (!validDir) {
                continue;
            }
            double base = orderScore.getOrDefault(neighborId, 0.0);
            // Add subtle port-index tie-breaker so multi-port connections align cleanly
            double portBias = portOrderFraction(working.get(neighborId), usePredecessors ? edge.fromPortId() : edge.toPortId(), usePredecessors);
            sum += base + portBias * 0.25;
            count++;
        }
        return count > 0 ? (sum / count) : orderScore.getOrDefault(nodeId, 0.0);
    }

    private static double portOrderFraction(PlanNode node, String portId, boolean outputPort) {
        if (!(node instanceof ProcessNode proc)) {
            return 0.0;
        }
        List<ResourcePort> ports = outputPort ? proc.outputs() : proc.inputs();
        if (ports.size() <= 1) {
            return 0.0;
        }
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).id().equals(portId)) {
                return ((double) i / (ports.size() - 1)) - 0.5;
            }
        }
        return 0.0;
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

    private static GroupNode recomputeGroupBounds(GroupNode group, Map<String, PlanNode> working) {
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
}
