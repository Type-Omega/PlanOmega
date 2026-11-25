package com.typeomega.planomega.export;

import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.solver.SolveResult.MachineBomEntry;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;

import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.Set;

/**
 * Exports {@link PlanGraph} into clean {@code flowchart LR} Mermaid syntax
 * compatible with GuideME and MkDocs documentation.
 */
public final class MermaidExporter {

    private static final DecimalFormat FMT = new DecimalFormat("#.##");

    private MermaidExporter() {
    }

    public static String exportMermaid(PlanGraph graph, SolveResult solve, AuditReport audit) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart LR\n");
        sb.append("    %% Plan Omega: ").append(escape(graph.name())).append('\n');
        sb.append("    classDef recipe fill:#161e2b,stroke:#334155,color:#f1f5f9\n");
        sb.append("    classDef sandbox fill:#312413,stroke:#f59e0b,stroke-dasharray:5 3,color:#fde68a\n");
        sb.append("    classDef flagged stroke:#ef4444,stroke-width:2px\n");

        Set<String> emittedNodes = new HashSet<>();

        // Emit grouped nodes inside Mermaid subgraphs
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof GroupNode group) {
                String gid = sanitizeId(group.id());
                sb.append("    subgraph ").append(gid).append("[\"").append(escape(group.title())).append("\"]\n");
                for (String childId : group.childNodeIds()) {
                    PlanNode child = graph.nodes().get(childId);
                    if (child instanceof ProcessNode proc && emittedNodes.add(proc.id())) {
                        appendNodeLine(sb, "        ", proc, solve, audit);
                    }
                }
                sb.append("    end\n");
            }
        }

        // Emit ungrouped ProcessNodes
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof ProcessNode proc && emittedNodes.add(proc.id())) {
                appendNodeLine(sb, "    ", proc, solve, audit);
            }
        }

        // Emit edges
        for (PlanEdge edge : graph.edges().values()) {
            String from = sanitizeId(edge.fromNodeId());
            String to = sanitizeId(edge.toNodeId());
            String label = edge.resource().shortName();
            Double flow = solve != null && solve.isSolved() ? solve.edgeFlows().get(edge.id()) : null;
            if (flow != null && flow > 1e-6) {
                String unit = edge.resource().kind() == ResourceKind.FLUID ? " mB/s" : "/s";
                label += " " + FMT.format(flow) + unit;
            }
            sb.append("    ").append(from).append(" -->|\"").append(escape(label)).append("\"| ").append(to).append('\n');
        }

        if (solve != null && solve.isSolved() && !solve.machineBom().isEmpty()) {
            sb.append("\n    %% Machine BOM\n");
            for (MachineBomEntry entry : solve.machineBom()) {
                String tierName = VoltageTier.byIndex(entry.tier()).shortName();
                sb.append("    %% - ").append(entry.machineCount()).append("x ")
                        .append(entry.displayName()).append(" (").append(tierName).append(')');
                if (entry.multiblock()) {
                    sb.append(" [IB:").append(entry.inputBuses())
                            .append(" OB:").append(entry.outputBuses())
                            .append(" IH:").append(entry.inputHatches())
                            .append(" OH:").append(entry.outputHatches())
                            .append(" EH:").append(entry.energyHatches());
                    if (entry.estimatedCoils() > 0) {
                        sb.append(" Coils:").append(entry.estimatedCoils());
                        if (entry.coilTier() != null) {
                            sb.append('(').append(entry.coilTier()).append(')');
                        }
                    }
                    if (entry.parallelHatches() > 0) {
                        sb.append(" PH:").append(entry.parallelHatches());
                    }
                    sb.append(']');
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    private static void appendNodeLine(
            StringBuilder sb,
            String indent,
            ProcessNode proc,
            SolveResult solve,
            AuditReport audit
    ) {
        String nid = sanitizeId(proc.id());
        String tier = VoltageTier.byIndex(proc.tier()).shortName();
        StringBuilder label = new StringBuilder(proc.title());
        label.append("<br/>").append(tier).append(" ").append(proc.eut()).append(" EU/t");

        NodeSolveStats stats = solve != null && solve.isSolved() ? solve.nodeStats().get(proc.id()) : null;
        if (stats != null && stats.craftsPerSecond() > 1e-6) {
            label.append(" • ").append(FMT.format(stats.machinesExact())).append("x");
        }
        if (proc.coilTier() != null) {
            label.append(" • ").append(proc.coilTier());
        }
        if (proc.parallelCount() > 1) {
            label.append(" • P").append(proc.parallelCount());
        }
        String cssClass = proc.isVirtual() ? "sandbox" : "recipe";
        if (audit != null && !audit.forNode(proc.id()).isEmpty()) {
            cssClass += ",flagged";
        }
        sb.append(indent).append(nid).append("[\"").append(escape(label.toString())).append("\"]:::")
                .append(cssClass).append('\n');
    }

    private static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String escape(String raw) {
        return raw.replace("\"", "'");
    }
}
