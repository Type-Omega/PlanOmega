package com.typeomega.planomega.audit;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Aggregated result of {@link ChainAuditor#audit} indexed globally and by node ID.
 */
public record AuditReport(
        List<FrictionFlag> flags,
        Map<String, List<FrictionFlag>> flagsByNode
) {

    public static final AuditReport EMPTY = new AuditReport(List.of(), Map.of());

    public AuditReport {
        flags = flags == null ? List.of() : List.copyOf(flags);
        flagsByNode = flagsByNode == null ? Map.of() : Collections.unmodifiableMap(flagsByNode);
    }

    public List<FrictionFlag> forNode(String nodeId) {
        return flagsByNode.getOrDefault(nodeId, List.of());
    }
}
