package com.typeomega.planomega;

import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.audit.ChainAuditor;
import com.typeomega.planomega.audit.FrictionFlag.Category;
import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.export.AiAnalysisExporter;
import com.typeomega.planomega.export.MermaidExporter;
import com.typeomega.planomega.export.SandboxDiffExporter;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.solver.ProductionSolver;
import com.typeomega.planomega.solver.SolveResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditAndExportTest {

    @Test
    void auditorDetectsEnergyHotspotAndOrphanByproduct() {
        RecipeNode electrolyzer = new RecipeNode(
                "elec", 0, 0, 0, 0, null, "gtceu:electrolyzer/water", "gtceu:electrolyzer", null,
                "Water Electrolysis",
                List.of(ResourcePort.input("in_h2o", ResourceKey.fluid("minecraft:water"), 1000)),
                List.of(
                        ResourcePort.output("out_h2", ResourceKey.fluid("gtceu:hydrogen"), 2000),
                        ResourcePort.output("out_o2", ResourceKey.fluid("gtceu:oxygen"), 1000)
                ),
                30, 100, 1, null, 0, false, false, 1, 2000.0, "out_h2", null
        );
        RecipeNode sideConsumer = new RecipeNode(
                "side", 250, 0, 0, 0, null, "gtceu:compressor/h2", "gtceu:compressor", null,
                "Side Step",
                List.of(ResourcePort.input("in_h2", ResourceKey.fluid("gtceu:hydrogen"), 2000)),
                List.of(ResourcePort.output("out_cell", ResourceKey.item("gtceu:hydrogen_cell"), 1)),
                2, 20, 0, null, 0, false, false, 1, 1.0, "out_cell", null
        );
        PlanGraph graph = PlanGraph.empty("Audit Test").withNode(electrolyzer).withNode(sideConsumer);
        SolveResult solve = ProductionSolver.solve(graph);
        AuditReport report = ChainAuditor.audit(graph, solve);

        assertTrue(report.flags().stream().anyMatch(f -> f.category() == Category.ORPHAN_BYPRODUCT
                && ResourceKey.fluid("gtceu:oxygen").equals(f.resource())));
        assertTrue(report.flags().stream().anyMatch(f -> f.category() == Category.BOTTLENECK_ENERGY));
    }

    @Test
    void aiJsonMermaidAndSandboxDiffExportsContainExpectedStructure() {
        PlanGraph graph = DemoGraphFactory.createSampleSulfuricAcidPlan();
        SolveResult solve = ProductionSolver.solve(graph);
        AuditReport audit = ChainAuditor.audit(graph, solve);

        String aiJson = AiAnalysisExporter.exportJson(graph, solve, audit);
        assertTrue(aiJson.contains("planomega.ai.analysis/v1"));
        assertTrue(aiJson.contains("gtceu:chemical_reactor/sulfuric_acid"));

        String mermaid = MermaidExporter.exportMermaid(graph, solve, audit);
        assertTrue(mermaid.startsWith("flowchart LR"));
        assertTrue(mermaid.contains("subgraph"));

        String diff = SandboxDiffExporter.toMarkdownDiff(graph, id -> Optional.empty());
        assertTrue(diff.contains("V2O5 Contact Catalysis"));

        String kubejs = SandboxDiffExporter.toKubeJsScript(graph);
        assertTrue(kubejs.contains("ServerEvents.recipes"));
        assertTrue(kubejs.contains("event.recipes.gtceu.large_chemical_reactor"));

        String javaCode = SandboxDiffExporter.toTypeOmegaJava(graph);
        assertTrue(javaCode.contains("GTRecipeTypes.LARGE_CHEMICAL_REACTOR.recipeBuilder"));
    }

    @Test
    void auditorAvoidsFalsePositivesForNonTrivialAndMultiInputSteps() {
        RecipeNode highCost1to1 = new RecipeNode(
                "n1", 0, 0, 0, 0, null, "gtceu:macerator/ore", "gtceu:macerator", null, "Macerator",
                List.of(ResourcePort.input("in", ResourceKey.item("minecraft:iron_ore"), 1)),
                List.of(ResourcePort.output("out", ResourceKey.item("gtceu:iron_dust"), 1)),
                120, 80, 2, null, 0, false, false, 1, null, null, null
        );
        RecipeNode hvMultiInputChem = new RecipeNode(
                "n2", 250, 0, 0, 0, null, "gtceu:chemical_reactor/steel", "gtceu:chemical_reactor", null, "Chem Step",
                List.of(
                        ResourcePort.input("in_a", ResourceKey.item("gtceu:iron_dust"), 1),
                        ResourcePort.input("in_b", ResourceKey.fluid("gtceu:oxygen"), 1000)
                ),
                List.of(ResourcePort.output("out_b", ResourceKey.item("gtceu:steel_dust"), 1)),
                480, 100, 3, null, 0, false, false, 1, 1.0, "out_b", null
        );
        PlanGraph graph = PlanGraph.empty("No False Positives")
                .withNode(highCost1to1)
                .withNode(hvMultiInputChem)
                .withEdge(new PlanEdge("e1", "n1", "out", "n2", "in_a", ResourceKey.item("gtceu:iron_dust"), true));

        SolveResult solve = ProductionSolver.solve(graph);
        AuditReport report = ChainAuditor.audit(graph, solve);
        assertFalse(report.flags().stream().anyMatch(f -> f.category() == Category.REDUNDANT_PASSTHROUGH));
        assertFalse(report.flags().stream().anyMatch(f -> f.category() == Category.ARTIFICIAL_JUMP));
    }

    @Test
    void sandboxExporterFormatsCatalystsCircuitsAndBlastFurnaceTemp() {
        VirtualRecipeNode ebf = new VirtualRecipeNode(
                "ebf_v", 0, 0, 0, 0, null, "gtceu:electric_blast_furnace/titanium",
                "gtceu:electric_blast_furnace", null, "Titanium Smelt",
                List.of(
                        ResourcePort.input("in_dust", ResourceKey.item("gtceu:titanium_dust"), 1),
                        ResourcePort.catalyst("cat_plate", ResourceKey.item("gtceu:integrated_circuit"), 1),
                        ResourcePort.input("in_gas", ResourceKey.fluid("gtceu:argon"), 500)
                ),
                List.of(
                        ResourcePort.output("out_ingot", ResourceKey.item("gtceu:titanium_ingot"), 1),
                        ResourcePort.output("out_gas", ResourceKey.fluid("gtceu:hot_argon"), 500)
                ),
                1920, 400, 4, 2, 2700, false, false, "EBF test", null, null
        );
        PlanGraph graph = PlanGraph.empty("EBF Sandbox").withNode(ebf);

        String kubejs = SandboxDiffExporter.toKubeJsScript(graph);
        assertTrue(kubejs.contains("event.remove({ id: 'gtceu:electric_blast_furnace/titanium' })"));
        assertTrue(kubejs.contains(".itemInputs('1x gtceu:titanium_dust')"));
        assertTrue(kubejs.contains(".notConsumable('1x gtceu:integrated_circuit')"));
        assertTrue(kubejs.contains(".inputFluids('gtceu:argon 500')"));
        assertTrue(kubejs.contains(".itemOutputs('1x gtceu:titanium_ingot')"));
        assertTrue(kubejs.contains(".outputFluids('gtceu:hot_argon 500')"));
        assertTrue(kubejs.contains(".circuit(2)"));
        assertTrue(kubejs.contains(".blastFurnaceTemp(2700)"));

        String javaCode = SandboxDiffExporter.toTypeOmegaJava(graph);
        assertTrue(javaCode.contains("GTRecipeTypes.ELECTRIC_BLAST_FURNACE.recipeBuilder(\"titanium_smelt\")"));
        assertTrue(javaCode.contains(".inputItems(\"gtceu:titanium_dust\", 1)"));
        assertTrue(javaCode.contains(".notConsumable(\"gtceu:integrated_circuit\", 1)"));
        assertTrue(javaCode.contains(".inputFluids(GTMaterials.get(\"argon\").getFluid(500))"));
        assertTrue(javaCode.contains(".outputItems(\"gtceu:titanium_ingot\", 1)"));
        assertTrue(javaCode.contains(".outputFluids(GTMaterials.get(\"hot_argon\").getFluid(500))"));
        assertTrue(javaCode.contains(".circuitMeta(2)"));
        assertTrue(javaCode.contains(".blastFurnaceTemp(2700)"));
    }
}
