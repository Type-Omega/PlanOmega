package com.typeomega.planomega.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.audit.FrictionFlag;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.solver.OverclockCalculator;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.solver.SolveResult.MachineBomEntry;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;

import java.util.Map;

/**
 * Exports a solved and audited {@link PlanGraph} into a rich, structured JSON payload
 * tailored for AI-assisted recipe balancing and realistic chemical chain engineering.
 */
public final class AiAnalysisExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private AiAnalysisExporter() {
    }

    public static String exportJson(PlanGraph graph, SolveResult solve, AuditReport audit) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "planomega.ai.analysis/v1");
        root.addProperty("planId", graph.id());
        root.addProperty("planName", graph.name());

        JsonObject summary = new JsonObject();
        summary.addProperty("solverStatus", solve.status().name());
        summary.addProperty("totalConsumptionEUt", round2(solve.totalConsumptionEut()));
        summary.addProperty("totalGenerationEUt", round2(solve.totalGenerationEut()));
        summary.addProperty("netEUt", round2(solve.netEut()));
        summary.addProperty("totalMachinesCeil", solve.totalMachinesCeil());
        summary.add("netImportsPerSec", serializeRateMap(solve.netImports()));
        summary.add("netExportsPerSec", serializeRateMap(solve.netExports()));
        summary.add("machineBom", serializeMachineBom(solve));
        root.add("summary", summary);

        JsonArray nodesArr = new JsonArray();
        for (PlanNode node : graph.nodes().values()) {
            if (!(node instanceof ProcessNode proc)) {
                continue;
            }
            NodeSolveStats stats = solve.nodeStats().get(proc.id());
            double craftsPerSec = stats != null ? stats.craftsPerSecond() : 0.0;
            int ocSteps = Math.max(0, proc.tier() - VoltageTier.fromEut(proc.eut()).index());

            JsonObject nObj = new JsonObject();
            nObj.addProperty("nodeId", proc.id());
            nObj.addProperty("kind", proc.isVirtual() ? "sandbox_virtual" : "recipe");
            nObj.addProperty("title", proc.title());
            if (proc instanceof RecipeNode r) {
                nObj.addProperty("recipeId", r.recipeId());
            } else if (proc instanceof VirtualRecipeNode v && v.baseRecipeId() != null) {
                nObj.addProperty("baseRecipeId", v.baseRecipeId());
            }
            nObj.addProperty("recipeTypeId", proc.recipeTypeId());
            if (proc.machineId() != null) {
                nObj.addProperty("machineId", proc.machineId());
            }
            nObj.addProperty("eut", proc.eut());
            nObj.addProperty("durationTicks", proc.durationTicks());
            nObj.addProperty("tier", VoltageTier.byIndex(proc.tier()).shortName());
            if (proc.circuit() != null) {
                nObj.addProperty("circuit", proc.circuit());
            }
            if (proc.coilTemp() > 0) {
                nObj.addProperty("coilTempK", proc.coilTemp());
            }
            if (proc.coilTier() != null) {
                nObj.addProperty("coilTier", proc.coilTier());
            }
            if (proc.parallelCount() > 1) {
                nObj.addProperty("parallelCount", proc.parallelCount());
            }
            if (proc.cleanroom()) {
                nObj.addProperty("cleanroom", true);
            }
            nObj.addProperty("perfectOverclock", proc.perfectOverclock());
            if (stats != null) {
                nObj.addProperty("craftsPerSec", round4(craftsPerSec));
                nObj.addProperty("machinesExact", round4(stats.machinesExact()));
                nObj.addProperty("machinesCeil", stats.machinesCeil());
                nObj.addProperty("effectiveEUt", round2(stats.totalEut()));
            }
            nObj.add("inputs", serializePorts(proc.inputs(), craftsPerSec, ocSteps));
            nObj.add("outputs", serializePorts(proc.outputs(), craftsPerSec, ocSteps));
            nodesArr.add(nObj);
        }
        root.add("nodes", nodesArr);

        JsonArray edgesArr = new JsonArray();
        for (PlanEdge edge : graph.edges().values()) {
            JsonObject eObj = new JsonObject();
            eObj.addProperty("fromNodeId", edge.fromNodeId());
            eObj.addProperty("toNodeId", edge.toNodeId());
            eObj.addProperty("resource", edge.resource().canonicalId());
            Double flow = solve.edgeFlows().get(edge.id());
            if (flow != null) {
                eObj.addProperty("flowPerSec", round4(flow));
            }
            edgesArr.add(eObj);
        }
        root.add("edges", edgesArr);

        JsonArray flagsArr = new JsonArray();
        for (FrictionFlag flag : audit.flags()) {
            JsonObject fObj = new JsonObject();
            fObj.addProperty("category", flag.category().name());
            fObj.addProperty("severity", flag.severity().name());
            if (flag.nodeId() != null) {
                fObj.addProperty("nodeId", flag.nodeId());
            }
            if (flag.resource() != null) {
                fObj.addProperty("resource", flag.resource().canonicalId());
            }
            fObj.addProperty("title", flag.title());
            fObj.addProperty("detail", flag.detail());
            fObj.addProperty("aiHint", flag.aiHint());
            flagsArr.add(fObj);
        }
        root.add("frictionFlags", flagsArr);

        return GSON.toJson(root);
    }

    private static JsonArray serializePorts(Iterable<ResourcePort> ports, double craftsPerSec, int ocSteps) {
        JsonArray arr = new JsonArray();
        for (ResourcePort p : ports) {
            JsonObject obj = new JsonObject();
            obj.addProperty("resource", p.resource().canonicalId());
            obj.addProperty("amountPerCraft", p.amount());
            if (p.chance() < 0.9999) {
                obj.addProperty("chance", p.chance());
            }
            if (p.catalyst()) {
                obj.addProperty("catalyst", true);
            }
            double perSec = OverclockCalculator.expectedAmountPerCraft(p, ocSteps) * craftsPerSec;
            obj.addProperty("ratePerSec", round4(perSec));
            arr.add(obj);
        }
        return arr;
    }

    private static JsonArray serializeMachineBom(SolveResult solve) {
        JsonArray arr = new JsonArray();
        for (MachineBomEntry entry : solve.machineBom()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("machineKey", entry.machineKey());
            obj.addProperty("displayName", entry.displayName());
            obj.addProperty("tier", VoltageTier.byIndex(entry.tier()).shortName());
            obj.addProperty("machineCount", entry.machineCount());
            obj.addProperty("multiblock", entry.multiblock());
            if (entry.multiblock()) {
                obj.addProperty("inputBuses", entry.inputBuses());
                obj.addProperty("outputBuses", entry.outputBuses());
                obj.addProperty("inputHatches", entry.inputHatches());
                obj.addProperty("outputHatches", entry.outputHatches());
                obj.addProperty("energyHatches", entry.energyHatches());
                if (entry.coilTier() != null) {
                    obj.addProperty("coilTier", entry.coilTier());
                }
                if (entry.estimatedCoils() > 0) {
                    obj.addProperty("estimatedCoils", entry.estimatedCoils());
                }
                if (entry.parallelHatches() > 0) {
                    obj.addProperty("parallelHatches", entry.parallelHatches());
                }
            }
            arr.add(obj);
        }
        return arr;
    }

    private static JsonObject serializeRateMap(Map<ResourceKey, Double> map) {
        JsonObject obj = new JsonObject();
        for (Map.Entry<ResourceKey, Double> e : map.entrySet()) {
            obj.addProperty(e.getKey().canonicalId(), round4(e.getValue()));
        }
        return obj;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
