package com.typeomega.planomega.solver;

import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.solver.OverclockCalculator.OverclockSpec;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;
import com.typeomega.planomega.solver.SolveResult.Status;
import org.ojalgo.optimisation.Expression;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linear-programming production balancer powered by {@code ojAlgo}.
 * Balances flow conservation across arbitrary DAGs and closed recycling loops,
 * computes explicit edge flow rates, machine counts, overclocked EU/t, and net I/O.
 */
public final class ProductionSolver {

    private static final double EPS = 1e-6;

    private ProductionSolver() {
    }

    public static SolveResult solve(PlanGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return SolveResult.NO_TARGETS;
        }
        List<ProcessNode> processNodes = new ArrayList<>();
        boolean hasAnyTarget = false;
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof ProcessNode proc) {
                processNodes.add(proc);
                if (proc.targetRate() != null && Double.isFinite(proc.targetRate()) && proc.targetRate() > 0.0) {
                    hasAnyTarget = true;
                }
                if (proc instanceof RecipeNode r && r.lockedRate() != null) {
                    if (!Double.isFinite(r.lockedRate()) || r.lockedRate() < 0.0) {
                        return new SolveResult(Status.INFEASIBLE, Map.of(), Map.of(), Map.of(), Map.of(),
                                0, 0, 0,
                                "Infeasible: node '" + r.title() + "' (" + r.id() + ") has invalid lockedRate: " + r.lockedRate());
                    }
                    if (r.lockedRate() > 0.0) {
                        hasAnyTarget = true;
                    }
                }
            }
        }
        if (processNodes.isEmpty() || !hasAnyTarget) {
            return SolveResult.NO_TARGETS;
        }

        try {
            return solveInternal(graph, processNodes);
        } catch (Exception e) {
            return new SolveResult(Status.ERROR, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, e.getMessage());
        }
    }

    private static SolveResult solveInternal(PlanGraph graph, List<ProcessNode> processNodes) {
        ExpressionsBasedModel model = new ExpressionsBasedModel();

        Map<String, ProcessNode> nodeMap = new LinkedHashMap<>();
        Map<String, OverclockSpec> specs = new LinkedHashMap<>();
        Map<String, Variable> rateVars = new LinkedHashMap<>();
        Map<String, Variable> edgeVars = new LinkedHashMap<>();
        Map<ResourceKey, Variable> importVars = new LinkedHashMap<>();
        Map<ResourceKey, Variable> exportVars = new LinkedHashMap<>();

        // 1. Node rate variables (crafts/s)
        for (ProcessNode node : processNodes) {
            nodeMap.put(node.id(), node);
            OverclockSpec spec = OverclockCalculator.compute(node);
            specs.put(node.id(), spec);

            double euWeight = Math.max(0.0, spec.effectiveEut()) / Math.max(1.0, spec.craftsPerSecondPerMachine()) * 1e-4;
            Variable r = model.addVariable("rate_" + node.id())
                    .lower(0.0)
                    .weight(0.05 + euWeight);

            if (node instanceof RecipeNode rec && rec.lockedRate() != null) {
                r.level(Math.max(0.0, rec.lockedRate()));
            }
            rateVars.put(node.id(), r);

            // Collect all resource keys
            for (ResourcePort in : node.inputs()) {
                if (!in.catalyst()) {
                    importVars.computeIfAbsent(in.resource(), k -> model.addVariable("imp_" + k.canonicalId()).lower(0.0).weight(importWeight(k)));
                    exportVars.computeIfAbsent(in.resource(), k -> model.addVariable("exp_" + k.canonicalId()).lower(0.0).weight(exportWeight(k)));
                }
            }
            for (ResourcePort out : node.outputs()) {
                importVars.computeIfAbsent(out.resource(), k -> model.addVariable("imp_" + k.canonicalId()).lower(0.0).weight(importWeight(k)));
                exportVars.computeIfAbsent(out.resource(), k -> model.addVariable("exp_" + k.canonicalId()).lower(0.0).weight(exportWeight(k)));
            }
        }

        // 2. Target constraints & pre-solve conflict diagnostics
        for (ProcessNode node : processNodes) {
            Double targetRate = node.targetRate();
            if (targetRate == null || !Double.isFinite(targetRate) || targetRate <= 0.0) {
                continue;
            }
            OverclockSpec spec = specs.get(node.id());
            Variable r = rateVars.get(node.id());
            ResourcePort targetPort = resolveTargetPort(node);
            if (targetPort == null) {
                String reason = node.targetPortId() != null
                        ? "missing targetPortId '" + node.targetPortId() + "'"
                        : "no output ports";
                return new SolveResult(Status.INFEASIBLE, Map.of(), Map.of(), Map.of(), Map.of(),
                        0, 0, 0,
                        "Infeasible: node '" + node.title() + "' (" + node.id() + ") has targetRate "
                                + targetRate + " but " + reason + ".");
            }
            double perCraft = OverclockCalculator.expectedAmountPerCraft(targetPort, spec.ocSteps());
            if (perCraft <= EPS) {
                return new SolveResult(Status.INFEASIBLE, Map.of(), Map.of(), Map.of(), Map.of(),
                        0, 0, 0,
                        "Infeasible: node '" + node.title() + "' (" + node.id() + ") target resource "
                                + targetPort.resource().canonicalId() + " has zero expected yield per craft.");
            }
            if (node instanceof RecipeNode rec && rec.lockedRate() != null) {
                double lockedYield = perCraft * Math.max(0.0, rec.lockedRate());
                if (lockedYield + EPS < targetRate) {
                    return new SolveResult(Status.INFEASIBLE, Map.of(), Map.of(), Map.of(), Map.of(),
                            0, 0, 0,
                            String.format("Infeasible: node '%s' (%s) lockedRate %.4f/s yields %.4f/s of %s, below targetRate %.4f/s.",
                                    node.title(), node.id(), rec.lockedRate(), lockedYield,
                                    targetPort.resource().canonicalId(), targetRate));
                }
            }
            model.addExpression("target_" + node.id())
                    .set(r, perCraft)
                    .lower(targetRate);
        }

        // 3. Explicit edge flow variables and port capacity constraints
        Map<String, List<PlanEdge>> outgoingByPortKey = new LinkedHashMap<>();
        Map<String, List<PlanEdge>> incomingByPortKey = new LinkedHashMap<>();

        for (PlanEdge edge : graph.edges().values()) {
            ProcessNode fromNode = nodeMap.get(edge.fromNodeId());
            ProcessNode toNode = nodeMap.get(edge.toNodeId());
            if (fromNode == null || toNode == null) {
                continue;
            }
            if (fromNode.findPort(edge.fromPortId()).isEmpty() || toNode.findPort(edge.toPortId()).isEmpty()) {
                continue;
            }
            Variable f = model.addVariable("edge_" + edge.id())
                    .lower(0.0)
                    .weight(0.0);
            edgeVars.put(edge.id(), f);

            outgoingByPortKey.computeIfAbsent(portKey(edge.fromNodeId(), edge.fromPortId()), k -> new ArrayList<>()).add(edge);
            incomingByPortKey.computeIfAbsent(portKey(edge.toNodeId(), edge.toPortId()), k -> new ArrayList<>()).add(edge);
        }

        for (ProcessNode node : processNodes) {
            OverclockSpec spec = specs.get(node.id());
            Variable r = rateVars.get(node.id());
            for (ResourcePort out : node.outputs()) {
                List<PlanEdge> outEdges = outgoingByPortKey.get(portKey(node.id(), out.id()));
                if (outEdges == null || outEdges.isEmpty()) {
                    continue;
                }
                double perCraft = OverclockCalculator.expectedAmountPerCraft(out, spec.ocSteps());
                Expression cap = model.addExpression("outcap_" + node.id() + "_" + out.id()).upper(0.0);
                cap.set(r, -perCraft);
                for (PlanEdge e : outEdges) {
                    cap.set(edgeVars.get(e.id()), 1.0);
                }
            }
            for (ResourcePort in : node.inputs()) {
                List<PlanEdge> inEdges = incomingByPortKey.get(portKey(node.id(), in.id()));
                if (inEdges == null || inEdges.isEmpty()) {
                    continue;
                }
                double perCraft = OverclockCalculator.expectedAmountPerCraft(in, spec.ocSteps());
                Variable bypass = model.addVariable("bypass_" + node.id() + "_" + in.id())
                        .lower(0.0)
                        .weight(bypassWeight(in.resource()));
                Expression cap = model.addExpression("incap_" + node.id() + "_" + in.id()).level(0.0);
                cap.set(r, -perCraft);
                cap.set(bypass, 1.0);
                for (PlanEdge e : inEdges) {
                    cap.set(edgeVars.get(e.id()), 1.0);
                }
            }
        }

        // 4. Global resource mass-balance constraints: sum(produced) + import - sum(consumed) - export = 0
        for (ResourceKey key : importVars.keySet()) {
            Expression balance = model.addExpression("bal_" + key.canonicalId()).level(0.0);
            balance.set(importVars.get(key), 1.0);
            balance.set(exportVars.get(key), -1.0);

            for (ProcessNode node : processNodes) {
                OverclockSpec spec = specs.get(node.id());
                Variable r = rateVars.get(node.id());
                double netCoeff = 0.0;
                for (ResourcePort out : node.outputs()) {
                    if (out.resource().equals(key)) {
                        netCoeff += OverclockCalculator.expectedAmountPerCraft(out, spec.ocSteps());
                    }
                }
                for (ResourcePort in : node.inputs()) {
                    if (in.resource().equals(key)) {
                        netCoeff -= OverclockCalculator.expectedAmountPerCraft(in, spec.ocSteps());
                    }
                }
                if (Math.abs(netCoeff) > EPS) {
                    balance.set(r, netCoeff);
                }
            }
        }

        Optimisation.Result result = model.minimise();
        Optimisation.State state = result.getState();
        if (!state.isFeasible()) {
            String diagnostic = diagnoseInfeasibility(processNodes, specs, state);
            return new SolveResult(Status.INFEASIBLE, Map.of(), Map.of(), Map.of(), Map.of(),
                    0, 0, 0, diagnostic);
        }

        Map<String, NodeSolveStats> nodeStats = new LinkedHashMap<>();
        double totalConsumedEu = 0.0;
        double totalGeneratedEu = 0.0;
        int totalMachinesCeil = 0;

        for (ProcessNode node : processNodes) {
            double craftsPerSec = Math.max(0.0, result.get(model.indexOf(rateVars.get(node.id()))).doubleValue());
            OverclockSpec spec = specs.get(node.id());
            double machinesExact = craftsPerSec / spec.craftsPerSecondPerMachine();
            int machinesCeil = machinesExact > EPS ? (int) Math.ceil(machinesExact - EPS) : 0;
            double totalEut = machinesExact * spec.effectiveEut();
            double peakEut = machinesCeil * (double) spec.effectiveEut();

            if (totalEut >= 0) {
                totalConsumedEu += totalEut;
            } else {
                totalGeneratedEu += -totalEut;
            }
            totalMachinesCeil += machinesCeil;

            nodeStats.put(node.id(), new NodeSolveStats(
                    node.id(),
                    craftsPerSec,
                    machinesExact,
                    machinesCeil,
                    spec.effectiveEut(),
                    totalEut,
                    peakEut,
                    spec.effectiveTicks(),
                    spec.subtickParallels(),
                    spec.machineTier()
            ));
        }

        Map<String, Double> edgeFlows = new LinkedHashMap<>();
        for (Map.Entry<String, Variable> entry : edgeVars.entrySet()) {
            double flow = Math.max(0.0, result.get(model.indexOf(entry.getValue())).doubleValue());
            edgeFlows.put(entry.getKey(), flow);
        }

        Map<ResourceKey, Double> netImports = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey, Variable> entry : importVars.entrySet()) {
            double val = result.get(model.indexOf(entry.getValue())).doubleValue();
            if (val > EPS) {
                netImports.put(entry.getKey(), val);
            }
        }

        Map<ResourceKey, Double> netExports = new LinkedHashMap<>();
        for (Map.Entry<ResourceKey, Variable> entry : exportVars.entrySet()) {
            double val = result.get(model.indexOf(entry.getValue())).doubleValue();
            if (val > EPS) {
                netExports.put(entry.getKey(), val);
            }
        }

        Status status = state.isOptimal() ? Status.OPTIMAL : Status.FEASIBLE;
        return new SolveResult(
                status,
                nodeStats,
                edgeFlows,
                netImports,
                netExports,
                totalConsumedEu,
                totalGeneratedEu,
                totalMachinesCeil,
                null
        );
    }

    private static String diagnoseInfeasibility(
            List<ProcessNode> processNodes,
            Map<String, OverclockSpec> specs,
            Optimisation.State state
    ) {
        ExpressionsBasedModel diag = new ExpressionsBasedModel();
        Map<String, Variable> rateVars = new LinkedHashMap<>();
        List<SlackProbe> probes = new ArrayList<>();

        for (ProcessNode node : processNodes) {
            Variable r = diag.addVariable("r_" + node.id()).lower(0.0).weight(0.01);
            rateVars.put(node.id(), r);

            if (node instanceof RecipeNode rec && rec.lockedRate() != null) {
                double locked = Math.max(0.0, rec.lockedRate());
                Variable slackLo = diag.addVariable("sl_lo_" + node.id()).lower(0.0).weight(1e9);
                Variable slackHi = diag.addVariable("sl_hi_" + node.id()).lower(0.0).weight(1e9);
                diag.addExpression("dlock_" + node.id())
                        .set(r, 1.0)
                        .set(slackLo, 1.0)
                        .set(slackHi, -1.0)
                        .level(locked);
                probes.add(new SlackProbe(node, null, "lockedRate " + String.format("%.4f/s", locked), slackLo, slackHi));
            }

            Double targetRate = node.targetRate();
            if (targetRate != null && Double.isFinite(targetRate) && targetRate > 0.0) {
                ResourcePort targetPort = resolveTargetPort(node);
                if (targetPort != null) {
                    double perCraft = OverclockCalculator.expectedAmountPerCraft(targetPort, specs.get(node.id()).ocSteps());
                    if (perCraft > EPS) {
                        Variable slackTarget = diag.addVariable("st_" + node.id()).lower(0.0).weight(1e9);
                        diag.addExpression("dtarget_" + node.id())
                                .set(r, perCraft)
                                .set(slackTarget, 1.0)
                                .lower(targetRate);
                        probes.add(new SlackProbe(node, targetPort.resource(),
                                "targetRate " + String.format("%.4f/s", targetRate), slackTarget, null));
                    }
                }
            }
        }

        Optimisation.Result diagResult = diag.minimise();
        if (diagResult.getState().isFeasible()) {
            List<String> conflicts = new ArrayList<>();
            for (SlackProbe probe : probes) {
                double lo = diagResult.get(diag.indexOf(probe.slackA())).doubleValue();
                double hi = probe.slackB() != null ? diagResult.get(diag.indexOf(probe.slackB())).doubleValue() : 0.0;
                if (lo > EPS || hi > EPS) {
                    String resPart = probe.resource() != null ? " [" + probe.resource().canonicalId() + "]" : "";
                    conflicts.add("node '" + probe.node().title() + "' (" + probe.node().id() + ")"
                            + resPart + " (" + probe.detail() + ", deficit=" + String.format("%.4f", Math.max(lo, hi)) + ")");
                }
            }
            if (!conflicts.isEmpty()) {
                return "Infeasible (" + state.name() + "): " + String.join("; ", conflicts);
            }
        }
        return "Infeasible graph (solver state: " + state.name() + ")";
    }

    private record SlackProbe(
            ProcessNode node,
            ResourceKey resource,
            String detail,
            Variable slackA,
            Variable slackB
    ) {
    }

    private static ResourcePort resolveTargetPort(ProcessNode node) {
        if (node.targetPortId() != null) {
            return node.findPort(node.targetPortId()).orElse(null);
        }
        return node.outputs().isEmpty() ? null : node.outputs().get(0);
    }

    private static String portKey(String nodeId, String portId) {
        return nodeId + "::" + portId;
    }

    private static double importWeight(ResourceKey key) {
        return switch (key.kind()) {
            case FLUID -> 0.01;
            case ENERGY -> 0.01;
            case ITEM, TAG -> 10.0;
        };
    }

    private static double exportWeight(ResourceKey key) {
        return switch (key.kind()) {
            case FLUID -> 0.0005;
            case ENERGY -> 0.0;
            case ITEM, TAG -> 0.5;
        };
    }

    private static double bypassWeight(ResourceKey key) {
        return switch (key.kind()) {
            case FLUID -> 1.0;
            case ENERGY -> 1.0;
            case ITEM, TAG -> 1000.0;
        };
    }
}
