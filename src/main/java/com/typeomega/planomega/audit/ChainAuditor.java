package com.typeomega.planomega.audit;

import com.typeomega.planomega.audit.FrictionFlag.Category;
import com.typeomega.planomega.audit.FrictionFlag.Severity;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic chain auditor that inspects {@link PlanGraph} topology and {@link SolveResult}
 * metrics to surface orphan byproducts, severe bottlenecks, redundant steps, and artificial jumps.
 */
public final class ChainAuditor {

    private ChainAuditor() {
    }

    public static AuditReport audit(PlanGraph graph, SolveResult solve) {
        List<FrictionFlag> flags = new ArrayList<>();
        Map<String, List<FrictionFlag>> byNode = new LinkedHashMap<>();

        List<ProcessNode> activeNodes = new ArrayList<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();

        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof ProcessNode proc) {
                activeNodes.add(proc);
            }
        }
        for (PlanEdge edge : graph.edges().values()) {
            outDegree.merge(edge.fromNodeId(), 1, Integer::sum);
            inDegree.merge(edge.toNodeId(), 1, Integer::sum);
        }

        // Collect explicitly targeted outputs so intended final products are not flagged as orphan byproducts
        Set<ResourceKey> intendedTargetResources = new HashSet<>();
        for (ProcessNode proc : activeNodes) {
            if (proc.targetRate() != null && proc.targetRate() > 0.0) {
                if (proc.targetPortId() != null) {
                    proc.findPort(proc.targetPortId()).ifPresent(p -> intendedTargetResources.add(p.resource()));
                } else if (!proc.outputs().isEmpty()) {
                    intendedTargetResources.add(proc.outputs().get(0).resource());
                }
            }
        }

        // 1. Solved-metric audits (bottlenecks, orphan byproducts, recycle imbalance)
        if (solve != null && solve.isSolved()) {
            auditSolvedMetrics(activeNodes, solve, intendedTargetResources, flags);
        }

        // 2. Structural & progression audits (redundant passthroughs, artificial direct jumps)
        for (ProcessNode proc : activeNodes) {
            int ins = proc.inputs().size();
            int outs = proc.outputs().size();

            if (ins == 1 && outs == 1) {
                ResourcePort in = proc.inputs().get(0);
                ResourcePort out = proc.outputs().get(0);
                boolean sameResource = in.resource().equals(out.resource());
                boolean inSeries = inDegree.getOrDefault(proc.id(), 0) >= 1
                        && outDegree.getOrDefault(proc.id(), 0) >= 1;
                boolean trivialCost = proc.eut() >= 0 && proc.eut() <= 32 && proc.durationTicks() <= 40;
                boolean noSpecialConditions = proc.circuit() == null && proc.coilTemp() <= 0 && !proc.cleanroom();

                if (!in.catalyst() && inSeries && trivialCost && noSpecialConditions
                        && (sameResource
                        || (in.resource().kind() == out.resource().kind()
                        && out.chance() >= 1.0 - 1e-6
                        && !isStandardSingleStepMachine(proc.recipeTypeId())))) {
                    flags.add(new FrictionFlag(
                            Category.REDUNDANT_PASSTHROUGH,
                            Severity.INFO,
                            proc.id(),
                            out.resource(),
                            "Redundant passthrough: " + proc.title(),
                            "Single 1->1 low-cost step in series; candidate for folding or simplification.",
                            "Consider merging this trivial 1:1 step into the adjacent recipe or removing unnecessary intermediate."
                    ));
                }

                int baseRecipeTier = VoltageTier.fromEut(proc.eut()).index();
                if (proc.eut() > 0
                        && baseRecipeTier >= 3
                        && !in.catalyst()
                        && proc.circuit() == null
                        && proc.coilTemp() <= 0
                        && !sameResource
                        && proc.recipeTypeId().contains("chemical_reactor")) {
                    flags.add(new FrictionFlag(
                            Category.ARTIFICIAL_JUMP,
                            Severity.INFO,
                            proc.id(),
                            out.resource(),
                            "Single-reactant HV+ synthesis: " + proc.title(),
                            "High-tier chemical step with only 1 input; may lack realistic co-reagents or byproducts.",
                            "Candidate for realistic stoichiometry expansion (e.g., oxidizer/solvent/catalyst + balanced byproduct)."
                    ));
                }
            }

            if (proc.durationTicks() >= 600) {
                flags.add(new FrictionFlag(
                        Category.BOTTLENECK_TIME,
                        Severity.WARNING,
                        proc.id(),
                        proc.outputs().isEmpty() ? null : proc.outputs().get(0).resource(),
                        "Long cycle time: " + proc.title(),
                        String.format("Base duration is %.1fs (%d ticks).", proc.durationTicks() / 20.0, proc.durationTicks()),
                        "Evaluate overclocking, catalyst addition, or parallel multiblock support."
                ));
            }
        }

        for (FrictionFlag flag : flags) {
            if (flag.nodeId() != null) {
                byNode.computeIfAbsent(flag.nodeId(), k -> new ArrayList<>()).add(flag);
            }
        }
        return new AuditReport(flags, byNode);
    }

    private static void auditSolvedMetrics(
            List<ProcessNode> nodes,
            SolveResult solve,
            Set<ResourceKey> intendedTargets,
            List<FrictionFlag> flags
    ) {
        long activeCount = solve.nodeStats().values().stream()
                .filter(s -> s.craftsPerSecond() > 1e-6)
                .count();

        for (ProcessNode proc : nodes) {
            NodeSolveStats stats = solve.nodeStats().get(proc.id());
            if (stats == null || stats.craftsPerSecond() <= 1e-6) {
                continue;
            }
            if (activeCount >= 2 && solve.totalConsumptionEut() > 0.0
                    && stats.totalEut() / solve.totalConsumptionEut() >= 0.45) {
                double pct = (stats.totalEut() / solve.totalConsumptionEut()) * 100.0;
                flags.add(new FrictionFlag(
                        Category.BOTTLENECK_ENERGY,
                        Severity.WARNING,
                        proc.id(),
                        null,
                        String.format("Energy hotspot (%.0f%% EU/t): %s", pct, proc.title()),
                        String.format("Consumes %.1f EU/t out of %.1f EU/t total.", stats.totalEut(), solve.totalConsumptionEut()),
                        "Optimize recipe EUt, add catalytic route, or upgrade multiblock efficiency."
                ));
            }
            if (stats.machinesCeil() >= 16) {
                flags.add(new FrictionFlag(
                        Category.BOTTLENECK_MACHINES,
                        Severity.CRITICAL,
                        proc.id(),
                        null,
                        String.format("Machine spam (%dx): %s", stats.machinesCeil(), proc.title()),
                        String.format("Requires %d machines at %s.", stats.machinesCeil(),
                                com.typeomega.planomega.model.VoltageTier.byIndex(stats.machineTier()).shortName()),
                        "Increase machine tier/overclock, enable parallels, or shorten recipe duration."
                ));
            }
        }

        // Orphan byproducts & recycle imbalances
        for (Map.Entry<ResourceKey, Double> exp : solve.netExports().entrySet()) {
            ResourceKey key = exp.getKey();
            if (intendedTargets.contains(key)) {
                continue;
            }
            String producerNodeId = findProducerNode(nodes, solve, key);
            if (solve.netImports().containsKey(key)) {
                flags.add(new FrictionFlag(
                        Category.RECYCLE_IMBALANCE,
                        Severity.WARNING,
                        producerNodeId,
                        key,
                        "Recycle imbalance: " + key.shortName(),
                        String.format("Both imported (%.2f/s) and exported (%.2f/s) in net balance.",
                                solve.netImports().get(key), exp.getValue()),
                        "Wire the producer output back into the consumer input to close the loop."
                ));
            } else {
                flags.add(new FrictionFlag(
                        Category.ORPHAN_BYPRODUCT,
                        Severity.WARNING,
                        producerNodeId,
                        key,
                        "Orphan byproduct: " + key.shortName(),
                        String.format("Unconsumed surplus of %.2f/s.", exp.getValue()),
                        "Opportunity for closed-loop recycling, secondary chemical valorization, or voiding."
                ));
            }
        }
    }

    private static boolean isStandardSingleStepMachine(String recipeTypeId) {
        if (recipeTypeId == null) {
            return false;
        }
        String lower = recipeTypeId.toLowerCase(Locale.ROOT);
        return lower.contains("macerator")
                || lower.contains("bender")
                || lower.contains("wiremill")
                || lower.contains("lathe")
                || lower.contains("compressor")
                || lower.contains("cutter")
                || lower.contains("extruder")
                || lower.contains("forge_hammer")
                || lower.contains("furnace")
                || lower.contains("smelting")
                || lower.contains("fluid_solidifier")
                || lower.contains("extractor")
                || lower.contains("canner")
                || lower.contains("distillery")
                || lower.contains("centrifuge")
                || lower.contains("electrolyzer")
                || lower.contains("polarizer")
                || lower.contains("forming_press")
                || lower.contains("sifter")
                || lower.contains("ore_washer");
    }

    private static String findProducerNode(List<ProcessNode> nodes, SolveResult solve, ResourceKey key) {
        for (ProcessNode proc : nodes) {
            NodeSolveStats stats = solve.nodeStats().get(proc.id());
            if (stats == null || stats.craftsPerSecond() <= 1e-6) {
                continue;
            }
            for (ResourcePort out : proc.outputs()) {
                if (out.resource().equals(key)) {
                    return proc.id();
                }
            }
        }
        return null;
    }
}
