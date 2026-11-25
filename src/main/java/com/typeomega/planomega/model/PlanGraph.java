package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Immutable, versioned production-planner graph containing recipe nodes, sandbox virtual nodes,
 * collapsible groups, sticky notes, and typed resource edges.
 */
public record PlanGraph(
        int schemaVersion,
        String id,
        String name,
        String author,
        String description,
        Map<String, PlanNode> nodes,
        Map<String, PlanEdge> edges
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final double GROUP_PADDING_X = 18.0;
    private static final double GROUP_PADDING_TOP = 34.0;
    private static final double GROUP_PADDING_BOTTOM = 16.0;

    public PlanGraph {
        if (schemaVersion <= 0) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        id = (id == null || id.isBlank()) ? newId("plan") : id;
        name = (name == null || name.isBlank()) ? "Untitled Plan" : name;
        author = author == null ? "" : author;
        description = description == null ? "" : description;
        nodes = nodes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        edges = edges == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(edges));
    }

    public static PlanGraph empty(String name) {
        return new PlanGraph(CURRENT_SCHEMA_VERSION, newId("plan"), name, "", "", Map.of(), Map.of());
    }

    public static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public PlanGraph withName(String newName) {
        return new PlanGraph(schemaVersion, id, newName, author, description, nodes, edges);
    }

    public PlanGraph withMetadata(String newName, String newAuthor, String newDescription) {
        return new PlanGraph(schemaVersion, id, newName, newAuthor, newDescription, nodes, edges);
    }

    public Optional<PlanNode> node(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public PlanGraph withNode(PlanNode node) {
        Objects.requireNonNull(node, "node");
        Map<String, PlanNode> nextNodes = new LinkedHashMap<>(nodes);
        nextNodes.put(node.id(), node);
        return new PlanGraph(schemaVersion, id, name, author, description, nextNodes, edges);
    }

    public PlanGraph withoutNode(String nodeId) {
        return withoutNodes(List.of(nodeId));
    }

    public PlanGraph withoutNodes(Collection<String> removeIds) {
        if (removeIds == null || removeIds.isEmpty()) {
            return this;
        }
        Set<String> toRemove = new HashSet<>(removeIds);
        Map<String, PlanNode> nextNodes = new LinkedHashMap<>();
        for (Map.Entry<String, PlanNode> entry : nodes.entrySet()) {
            String nid = entry.getKey();
            if (toRemove.contains(nid)) {
                continue;
            }
            PlanNode node = entry.getValue();
            if (node.groupId() != null && toRemove.contains(node.groupId())) {
                node = node.withGroupId(null);
            }
            if (node instanceof GroupNode group) {
                List<String> remaining = group.childNodeIds().stream()
                        .filter(childId -> !toRemove.contains(childId))
                        .toList();
                node = group.withChildNodeIds(remaining);
            }
            nextNodes.put(nid, node);
        }

        Map<String, PlanEdge> nextEdges = new LinkedHashMap<>();
        for (Map.Entry<String, PlanEdge> entry : edges.entrySet()) {
            PlanEdge edge = entry.getValue();
            if (!toRemove.contains(edge.fromNodeId()) && !toRemove.contains(edge.toNodeId())) {
                nextEdges.put(entry.getKey(), edge);
            }
        }
        return new PlanGraph(schemaVersion, id, name, author, description, nextNodes, nextEdges);
    }

    public PlanGraph withEdge(PlanEdge edge) {
        Objects.requireNonNull(edge, "edge");
        if (!nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
            throw new IllegalArgumentException("Edge endpoints must exist in PlanGraph: " + edge);
        }
        for (PlanEdge existing : edges.values()) {
            if (existing.connectsSameEndpoints(edge)) {
                return this;
            }
        }
        Map<String, PlanEdge> nextEdges = new LinkedHashMap<>(edges);
        nextEdges.put(edge.id(), edge);
        return new PlanGraph(schemaVersion, id, name, author, description, nodes, nextEdges);
    }

    public PlanGraph withoutEdge(String edgeId) {
        if (!edges.containsKey(edgeId)) {
            return this;
        }
        Map<String, PlanEdge> nextEdges = new LinkedHashMap<>(edges);
        nextEdges.remove(edgeId);
        return new PlanGraph(schemaVersion, id, name, author, description, nodes, nextEdges);
    }

    public PlanGraph withoutEdges(Collection<String> edgeIds) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return this;
        }
        Map<String, PlanEdge> nextEdges = new LinkedHashMap<>(edges);
        edgeIds.forEach(nextEdges::remove);
        return new PlanGraph(schemaVersion, id, name, author, description, nodes, nextEdges);
    }

    /**
     * Moves all specified nodes by (dx, dy). Moving a GroupNode also moves its descendants once,
     * and moving a child node expands its parent GroupNode bounds automatically.
     */
    public PlanGraph moveNodes(Collection<String> selectedIds, double dx, double dy) {
        if (selectedIds == null || selectedIds.isEmpty() || (dx == 0.0 && dy == 0.0)) {
            return this;
        }
        Set<String> moveSet = new HashSet<>();
        for (String idToMove : selectedIds) {
            collectWithGroupDescendants(idToMove, moveSet);
        }

        Map<String, PlanNode> nextNodes = new LinkedHashMap<>(nodes);
        Set<String> affectedParentGroups = new HashSet<>();
        for (String nid : moveSet) {
            PlanNode current = nextNodes.get(nid);
            if (current != null) {
                nextNodes.put(nid, current.withPosition(current.x() + dx, current.y() + dy));
                if (current.groupId() != null && !moveSet.contains(current.groupId())) {
                    affectedParentGroups.add(current.groupId());
                }
            }
        }

        for (String groupId : affectedParentGroups) {
            PlanNode maybeGroup = nextNodes.get(groupId);
            if (maybeGroup instanceof GroupNode group && !group.collapsed()) {
                nextNodes.put(groupId, recomputeGroupBounds(group, nextNodes));
            }
        }
        return new PlanGraph(schemaVersion, id, name, author, description, nextNodes, edges);
    }

    /**
     * Groups the given nodes into a new collapsible {@link GroupNode}.
     */
    public PlanGraph groupNodes(String title, Collection<String> memberIds, int color) {
        if (memberIds == null || memberIds.isEmpty()) {
            return this;
        }
        List<String> validMembers = new ArrayList<>();
        for (String nid : memberIds) {
            PlanNode node = nodes.get(nid);
            if (node != null && !(node instanceof GroupNode)) {
                validMembers.add(nid);
            }
        }
        if (validMembers.isEmpty()) {
            return this;
        }

        String groupId = newId("grp");
        Map<String, PlanNode> nextNodes = new LinkedHashMap<>(nodes);
        for (String memberId : validMembers) {
            PlanNode member = nextNodes.get(memberId);
            if (member.groupId() != null && nextNodes.get(member.groupId()) instanceof GroupNode oldGroup) {
                List<String> trimmed = oldGroup.childNodeIds().stream()
                        .filter(cid -> !cid.equals(memberId))
                        .toList();
                nextNodes.put(oldGroup.id(), oldGroup.withChildNodeIds(trimmed));
            }
            nextNodes.put(memberId, member.withGroupId(groupId));
        }

        GroupNode rawGroup = new GroupNode(groupId, 0, 0, 260, 180, null, title, color, false, validMembers);
        nextNodes.put(groupId, recomputeGroupBounds(rawGroup, nextNodes));
        return new PlanGraph(schemaVersion, id, name, author, description, nextNodes, edges);
    }

    public PlanGraph toggleGroupCollapsed(String groupId) {
        PlanNode node = nodes.get(groupId);
        if (!(node instanceof GroupNode group)) {
            return this;
        }
        GroupNode updated = group.withCollapsed(!group.collapsed());
        if (!updated.collapsed()) {
            updated = recomputeGroupBounds(updated, nodes);
        }
        return withNode(updated);
    }

    /**
     * Checks if a directed connection from an output port to an input port is valid.
     */
    public boolean canConnect(String fromNodeId, String fromPortId, String toNodeId, String toPortId) {
        if (fromNodeId == null || toNodeId == null || fromNodeId.equals(toNodeId)) {
            return false;
        }
        PlanNode fromNode = nodes.get(fromNodeId);
        PlanNode toNode = nodes.get(toNodeId);
        if (!(fromNode instanceof ProcessNode fromProc) || !(toNode instanceof ProcessNode toProc)) {
            return false;
        }
        Optional<ResourcePort> outOpt = fromProc.findPort(fromPortId);
        Optional<ResourcePort> inOpt = toProc.findPort(toPortId);
        if (outOpt.isEmpty() || inOpt.isEmpty()) {
            return false;
        }
        ResourcePort out = outOpt.get();
        ResourcePort in = inOpt.get();
        if (out.direction() != PortDirection.OUTPUT || in.direction() != PortDirection.INPUT) {
            return false;
        }
        if (!out.resource().isCompatibleWith(in.resource())) {
            return false;
        }
        for (PlanEdge edge : edges.values()) {
            if (edge.fromNodeId().equals(fromNodeId)
                    && edge.fromPortId().equals(fromPortId)
                    && edge.toNodeId().equals(toNodeId)
                    && edge.toPortId().equals(toPortId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if {@code nodeId} is hidden because an ancestor {@link GroupNode} is collapsed.
     */
    public boolean isHiddenByCollapsedGroup(String nodeId) {
        PlanNode current = nodes.get(nodeId);
        Set<String> seen = new HashSet<>();
        while (current != null && current.groupId() != null && seen.add(current.groupId())) {
            PlanNode parent = nodes.get(current.groupId());
            if (parent instanceof GroupNode group && group.collapsed()) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    /**
     * Resolves the visible node ID to route edges visually when a node sits inside a collapsed group.
     */
    public @Nullable String resolveVisibleHostId(String nodeId) {
        PlanNode current = nodes.get(nodeId);
        if (current == null) {
            return null;
        }
        String visibleId = nodeId;
        Set<String> seen = new HashSet<>();
        while (current != null && current.groupId() != null && seen.add(current.groupId())) {
            PlanNode parent = nodes.get(current.groupId());
            if (parent instanceof GroupNode group && group.collapsed()) {
                visibleId = group.id();
            }
            current = parent;
        }
        return visibleId;
    }

    private void collectWithGroupDescendants(String nodeId, Set<String> out) {
        if (!out.add(nodeId)) {
            return;
        }
        PlanNode node = nodes.get(nodeId);
        if (node instanceof GroupNode group) {
            for (String childId : group.childNodeIds()) {
                collectWithGroupDescendants(childId, out);
            }
        }
    }

    private static GroupNode recomputeGroupBounds(GroupNode group, Map<String, PlanNode> nodeMap) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (String childId : group.childNodeIds()) {
            PlanNode child = nodeMap.get(childId);
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
                minX - GROUP_PADDING_X,
                minY - GROUP_PADDING_TOP,
                (maxX - minX) + GROUP_PADDING_X * 2,
                (maxY - minY) + GROUP_PADDING_TOP + GROUP_PADDING_BOTTOM,
                group.groupId(),
                group.title(),
                group.color(),
                group.collapsed(),
                group.childNodeIds()
        );
    }

    /**
     * Extracts a self-contained subgraph containing {@code selectedIds} (plus any GroupNode descendants)
     * and all internal edges whose endpoints both lie within the extracted set.
     */
    public PlanGraph extractSubgraph(Collection<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return PlanGraph.empty(name);
        }
        Set<String> keepSet = new HashSet<>();
        for (String nid : selectedIds) {
            if (nodes.containsKey(nid)) {
                collectWithGroupDescendants(nid, keepSet);
            }
        }
        if (keepSet.isEmpty()) {
            return PlanGraph.empty(name);
        }

        Map<String, PlanNode> nextNodes = new LinkedHashMap<>();
        for (Map.Entry<String, PlanNode> entry : nodes.entrySet()) {
            if (!keepSet.contains(entry.getKey())) {
                continue;
            }
            PlanNode node = entry.getValue();
            if (node.groupId() != null && !keepSet.contains(node.groupId())) {
                node = node.withGroupId(null);
            }
            if (node instanceof GroupNode group) {
                List<String> filteredChildren = group.childNodeIds().stream()
                        .filter(keepSet::contains)
                        .toList();
                node = group.withChildNodeIds(filteredChildren);
            }
            nextNodes.put(entry.getKey(), node);
        }

        Map<String, PlanEdge> nextEdges = new LinkedHashMap<>();
        for (Map.Entry<String, PlanEdge> entry : edges.entrySet()) {
            PlanEdge edge = entry.getValue();
            if (keepSet.contains(edge.fromNodeId()) && keepSet.contains(edge.toNodeId())) {
                nextEdges.put(entry.getKey(), edge);
            }
        }

        return new PlanGraph(schemaVersion, newId("sub"), name, author, description, nextNodes, nextEdges);
    }

    /**
     * Merges all nodes and edges from {@code subgraph} into this graph, skipping edges that duplicate
     * an existing endpoint pair.
     */
    public PlanGraph mergeSubgraph(PlanGraph subgraph) {
        if (subgraph == null || subgraph.nodes().isEmpty()) {
            return this;
        }
        Map<String, PlanNode> nextNodes = new LinkedHashMap<>(nodes);
        nextNodes.putAll(subgraph.nodes());
        PlanGraph merged = new PlanGraph(schemaVersion, id, name, author, description, nextNodes, edges);
        for (PlanEdge edge : subgraph.edges().values()) {
            if (merged.nodes().containsKey(edge.fromNodeId()) && merged.nodes().containsKey(edge.toNodeId())) {
                merged = merged.withEdge(edge);
            }
        }
        return merged;
    }

    public record AutoConnectResult(PlanGraph graph, int connectionsAdded) {
    }

    public AutoConnectResult autoConnect(boolean closeLoops) {
        return autoConnect(closeLoops, null);
    }

    /**
     * Automatically connects disconnected OUTPUT -&gt; INPUT ports with matching {@link ResourceKey}
     * (exact match first, then {@code #tag} match). When {@code closeLoops} is false, connections
     * that would introduce a cycle are skipped; when true, recycled byproduct/catalyst loops are allowed.
     */
    public AutoConnectResult autoConnect(
            boolean closeLoops,
            @Nullable BiPredicate<ResourceKey, ResourceKey> customTagMatcher
    ) {
        Set<String> connectedInputs = new HashSet<>();
        Set<String> connectedOutputs = new HashSet<>();
        Map<String, Set<String>> adj = new HashMap<>();

        for (PlanEdge edge : edges.values()) {
            connectedOutputs.add(portKey(edge.fromNodeId(), edge.fromPortId()));
            connectedInputs.add(portKey(edge.toNodeId(), edge.toPortId()));
            adj.computeIfAbsent(edge.fromNodeId(), k -> new HashSet<>()).add(edge.toNodeId());
        }

        List<ProcessNode> processNodes = new ArrayList<>();
        for (PlanNode node : nodes.values()) {
            if (node instanceof ProcessNode proc) {
                processNodes.add(proc);
            }
        }

        PlanGraph working = this;
        int added = 0;

        for (int pass = 0; pass < 2; pass++) {
            boolean exactOnly = (pass == 0);
            for (ProcessNode toProc : processNodes) {
                for (ResourcePort inPort : toProc.inputs()) {
                    String inKey = portKey(toProc.id(), inPort.id());
                    if (connectedInputs.contains(inKey)) {
                        continue;
                    }
                    for (ProcessNode fromProc : processNodes) {
                        if (fromProc.id().equals(toProc.id())) {
                            continue;
                        }
                        boolean matched = false;
                        for (ResourcePort outPort : fromProc.outputs()) {
                            String outKey = portKey(fromProc.id(), outPort.id());
                            if (connectedOutputs.contains(outKey)) {
                                continue;
                            }
                            boolean compatible = exactOnly
                                    ? outPort.resource().equals(inPort.resource())
                                    : isTagCompatible(outPort.resource(), inPort.resource(), customTagMatcher);
                            if (!compatible) {
                                continue;
                            }
                            if (!working.canConnect(fromProc.id(), outPort.id(), toProc.id(), inPort.id())) {
                                continue;
                            }
                            if (!closeLoops && isReachable(adj, toProc.id(), fromProc.id())) {
                                continue;
                            }
                            PlanEdge edge = new PlanEdge(
                                    newId("edge"),
                                    fromProc.id(),
                                    outPort.id(),
                                    toProc.id(),
                                    inPort.id(),
                                    outPort.resource(),
                                    false
                            );
                            working = working.withEdge(edge);
                            connectedInputs.add(inKey);
                            connectedOutputs.add(outKey);
                            adj.computeIfAbsent(fromProc.id(), k -> new HashSet<>()).add(toProc.id());
                            added++;
                            matched = true;
                            break;
                        }
                        if (matched) {
                            break;
                        }
                    }
                }
            }
        }

        return new AutoConnectResult(working, added);
    }

    private static String portKey(String nodeId, String portId) {
        return nodeId + "\0" + portId;
    }

    private static boolean isReachable(Map<String, Set<String>> adj, String start, String target) {
        if (start.equals(target)) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, Set.of())) {
                if (next.equals(target)) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private static boolean isTagCompatible(
            ResourceKey out,
            ResourceKey in,
            @Nullable BiPredicate<ResourceKey, ResourceKey> customTagMatcher
    ) {
        if (out.equals(in)) {
            return true;
        }
        if (out.kind() == ResourceKind.TAG && in.kind() == ResourceKind.TAG) {
            return out.id().equals(in.id());
        }
        boolean oneTagOneItem = (out.kind() == ResourceKind.TAG && in.kind() == ResourceKind.ITEM)
                || (out.kind() == ResourceKind.ITEM && in.kind() == ResourceKind.TAG);
        if (!oneTagOneItem) {
            return false;
        }
        if (customTagMatcher != null && customTagMatcher.test(out, in)) {
            return true;
        }
        ResourceKey tagKey = out.kind() == ResourceKind.TAG ? out : in;
        ResourceKey itemKey = out.kind() == ResourceKind.ITEM ? out : in;
        return matchesTagHeuristic(tagKey.id(), itemKey.id());
    }

    private static boolean matchesTagHeuristic(String tagId, String itemId) {
        String tagPath = stripNamespace(tagId).toLowerCase(Locale.ROOT);
        String itemPath = stripNamespace(itemId).toLowerCase(Locale.ROOT);
        if (tagPath.isEmpty() || itemPath.isEmpty()) {
            return false;
        }
        String[] segments = tagPath.split("/");
        for (String seg : segments) {
            if (seg.isEmpty()) {
                continue;
            }
            String singular = (seg.length() > 3 && seg.endsWith("s")) ? seg.substring(0, seg.length() - 1) : seg;
            if (!itemPath.contains(seg) && !itemPath.contains(singular)) {
                return false;
            }
        }
        return true;
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
