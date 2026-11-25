package com.typeomega.planomega.model;

import java.util.Objects;

/**
 * Directed resource connection between an output port of one node and an input port of another.
 */
public record PlanEdge(
        String id,
        String fromNodeId,
        String fromPortId,
        String toNodeId,
        String toPortId,
        ResourceKey resource,
        boolean manualLock
) {

    public PlanEdge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(fromPortId, "fromPortId");
        Objects.requireNonNull(toNodeId, "toNodeId");
        Objects.requireNonNull(toPortId, "toPortId");
        Objects.requireNonNull(resource, "resource");
    }

    public boolean connectsSameEndpoints(PlanEdge other) {
        return fromNodeId.equals(other.fromNodeId)
                && fromPortId.equals(other.fromPortId)
                && toNodeId.equals(other.toNodeId)
                && toPortId.equals(other.toPortId);
    }
}
