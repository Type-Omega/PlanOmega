package com.typeomega.planomega;

import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.solver.OverclockCalculator;
import com.typeomega.planomega.solver.ProductionSolver;
import com.typeomega.planomega.solver.SolveResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSolverTest {

    @Test
    void overclockComputesNormalPerfectAndSubtickParallels() {
        // LV (32 EU/t), 80 ticks (4s), upgraded to HV (2 OC steps)
        RecipeNode normal = new RecipeNode(
                "n1", 0, 0, 0, 0, null, "r:1", "t:1", null, "Normal",
                List.of(), List.of(ResourcePort.output("o", ResourceKey.item("minecraft:iron_ingot"), 1)),
                30, 80, 3, null, 0, false, false, 1, null, null, null
        );
        OverclockCalculator.OverclockSpec ocNormal = OverclockCalculator.compute(normal);
        assertEquals(2, ocNormal.ocSteps());
        assertEquals(480L, ocNormal.effectiveEut());
        assertEquals(20, ocNormal.effectiveTicks());
        assertEquals(1, ocNormal.subtickParallels());
        assertEquals(1.0, ocNormal.craftsPerSecondPerMachine(), 1e-6);

        // Perfect OC (LCR): 80 ticks -> 5 ticks at +2 tiers, and 2 ticks -> subtick parallels at +3 tiers (IV)
        RecipeNode perfectSubtick = new RecipeNode(
                "n2", 0, 0, 0, 0, null, "r:2", "t:2", null, "Perfect",
                List.of(), List.of(ResourcePort.output("o", ResourceKey.item("minecraft:iron_ingot"), 1)),
                30, 8, 4, null, 0, false, true, 1, null, null, null
        );
        // LV (1) -> EV (4) = 3 OC steps, 4^3 = 64x speed => 8/64 = 0.125 ticks => 1 tick with 8 subtick parallels
        OverclockCalculator.OverclockSpec ocPerfect = OverclockCalculator.compute(perfectSubtick);
        assertEquals(3, ocPerfect.ocSteps());
        assertEquals(1, ocPerfect.effectiveTicks());
        assertEquals(8, ocPerfect.subtickParallels());
        assertEquals(160.0, ocPerfect.craftsPerSecondPerMachine(), 1e-6);
    }

    @Test
    void solvesSulfuricAcidChainWithExactRatesAndMachineCounts() {
        PlanGraph graph = DemoGraphFactory.createSampleSulfuricAcidPlan();
        SolveResult result = ProductionSolver.solve(graph);

        assertTrue(result.isSolved());
        assertEquals(1.0, result.nodeStats().get("node_h2so4").craftsPerSecond(), 1e-4);
        assertEquals(8.0, result.nodeStats().get("node_h2so4").machinesExact(), 1e-4);
        assertEquals(8, result.nodeStats().get("node_h2so4").machinesCeil());
        assertEquals(1000.0, result.edgeFlows().get("edge_so2_so3"), 1e-3);
        assertEquals(1000.0, result.edgeFlows().get("edge_so3_h2so4"), 1e-3);

        // Net raw imports: 1 sulfur/s, 3000 mB/s oxygen, 1000 mB/s water
        assertEquals(1.0, result.netImports().get(ResourceKey.item("gtceu:sulfur_dust")), 1e-4);
        assertEquals(3000.0, result.netImports().get(ResourceKey.fluid("gtceu:oxygen")), 1e-3);
        assertEquals(1000.0, result.netImports().get(ResourceKey.fluid("minecraft:water")), 1e-3);
        assertEquals(1000.0, result.netExports().get(ResourceKey.fluid("gtceu:sulfuric_acid")), 1e-3);
    }

    @Test
    void solvesClosedRecyclingLoopWithoutDivergence() {
        // Acid Leacher: consumes 1000 mB Acid + 1 Ore -> 1 Pure Ore + 700 mB Dirty Acid
        RecipeNode leacher = new RecipeNode(
                "leach", 0, 0, 0, 0, null, "r:leach", "t:leach", null, "Leacher",
                List.of(
                        ResourcePort.input("in_acid", ResourceKey.fluid("gtceu:acid"), 1000),
                        ResourcePort.input("in_ore", ResourceKey.item("gtceu:raw_ore"), 1)
                ),
                List.of(
                        ResourcePort.output("out_pure", ResourceKey.item("gtceu:pure_ore"), 1),
                        ResourcePort.output("out_dirty", ResourceKey.fluid("gtceu:dirty_acid"), 700)
                ),
                32, 20, 1, null, 0, false, false, 1, 1.0, "out_pure", null
        );
        // Acid Recovery: consumes 700 mB Dirty Acid -> 700 mB Acid
        RecipeNode recovery = new RecipeNode(
                "recov", 300, 0, 0, 0, null, "r:recov", "t:recov", null, "Recovery",
                List.of(ResourcePort.input("in_dirty", ResourceKey.fluid("gtceu:dirty_acid"), 700)),
                List.of(ResourcePort.output("out_acid", ResourceKey.fluid("gtceu:acid"), 700)),
                16, 20, 1, null, 0, false, false, 1, null, null, null
        );

        PlanGraph loop = PlanGraph.empty("Loop Test")
                .withNode(leacher)
                .withNode(recovery)
                .withEdge(new PlanEdge("e1", "leach", "out_dirty", "recov", "in_dirty", ResourceKey.fluid("gtceu:dirty_acid"), true))
                .withEdge(new PlanEdge("e2", "recov", "out_acid", "leach", "in_acid", ResourceKey.fluid("gtceu:acid"), true));

        SolveResult res = ProductionSolver.solve(loop);
        assertTrue(res.isSolved());
        assertEquals(1.0, res.nodeStats().get("leach").craftsPerSecond(), 1e-4);
        assertEquals(1.0, res.nodeStats().get("recov").craftsPerSecond(), 1e-4);
        assertEquals(700.0, res.edgeFlows().get("e1"), 1e-3);
        assertEquals(700.0, res.edgeFlows().get("e2"), 1e-3);
        // Net acid makeup is only 300 mB/s instead of 1000 mB/s!
        assertEquals(300.0, res.netImports().get(ResourceKey.fluid("gtceu:acid")), 1e-3);
        assertEquals(1.0, res.netExports().get(ResourceKey.item("gtceu:pure_ore")), 1e-4);
    }

    @Test
    void returnsNoTargetsWhenGraphHasNoTargets() {
        RecipeNode node = new RecipeNode(
                "n1", 0, 0, 0, 0, null, "r:1", "t:1", null, "Idle",
                List.of(), List.of(ResourcePort.output("o", ResourceKey.item("minecraft:iron_ingot"), 1)),
                30, 20, 1, null, 0, false, false, 1, null, null, null
        );
        SolveResult result = ProductionSolver.solve(PlanGraph.empty("No Targets").withNode(node));
        assertEquals(SolveResult.Status.NO_TARGETS, result.status());
        assertFalse(result.isSolved());
    }

    @Test
    void infeasibleLockedRateAndTargetConflictReportsInfeasible() {
        RecipeNode conflicting = new RecipeNode(
                "conflict", 0, 0, 0, 0, null, "r:conflict", "t:1", null, "Conflicting",
                List.of(), List.of(ResourcePort.output("out", ResourceKey.item("minecraft:iron_ingot"), 1)),
                30, 20, 1, null, 0, false, false, 1, 5.0, "out", 1.0
        );
        SolveResult result = ProductionSolver.solve(PlanGraph.empty("Infeasible").withNode(conflicting));
        assertEquals(SolveResult.Status.INFEASIBLE, result.status());
        assertFalse(result.isSolved());
    }

    @Test
    void solvesMultiTargetWithGeneratorAndConsumerNetEut() {
        RecipeNode generator = new RecipeNode(
                "gen", 0, 0, 0, 0, null, "r:gen", "gtceu:steam_turbine", null, "Steam Turbine",
                List.of(ResourcePort.input("in_steam", ResourceKey.fluid("gtceu:steam"), 640)),
                List.of(ResourcePort.output("out_distilled", ResourceKey.fluid("gtceu:distilled_water"), 4)),
                -32, 20, 1, null, 0, false, false, 1, 4.0, "out_distilled", null
        );
        RecipeNode consumer = new RecipeNode(
                "cons", 250, 0, 0, 0, null, "r:cons", "gtceu:macerator", null, "Macerator",
                List.of(ResourcePort.input("in_ore", ResourceKey.item("minecraft:iron_ore"), 1)),
                List.of(ResourcePort.output("out_dust", ResourceKey.item("gtceu:iron_dust"), 2)),
                16, 20, 1, null, 0, false, false, 1, 4.0, "out_dust", null
        );

        SolveResult res = ProductionSolver.solve(PlanGraph.empty("Gen+Cons").withNode(generator).withNode(consumer));
        assertTrue(res.isSolved());
        assertEquals(32.0, res.totalGenerationEut(), 1e-4);
        assertEquals(32.0, res.totalConsumptionEut(), 1e-4);
        assertEquals(0.0, res.netEut(), 1e-4);
    }

    @Test
    void solvesConvergingEdgesAndVirtualRecipeClosedLoop() {
        VirtualRecipeNode producerA = new VirtualRecipeNode(
                "pa", 0, 0, 0, 0, null, null, "gtceu:mixer", null, "Producer A",
                List.of(ResourcePort.input("in_recycle", ResourceKey.fluid("gtceu:coolant"), 100)),
                List.of(ResourcePort.output("out_mix", ResourceKey.fluid("gtceu:slurry"), 500)),
                16, 20, 1, null, 0, false, false, "", null, null
        );
        VirtualRecipeNode producerB = new VirtualRecipeNode(
                "pb", 0, 150, 0, 0, null, null, "gtceu:mixer", null, "Producer B",
                List.of(ResourcePort.input("in_raw", ResourceKey.item("minecraft:cobblestone"), 1)),
                List.of(ResourcePort.output("out_mix", ResourceKey.fluid("gtceu:slurry"), 500)),
                16, 20, 1, null, 0, false, false, "", 1.0, null
        );
        RecipeNode consumer = new RecipeNode(
                "cons", 300, 75, 0, 0, null, "r:cons", "gtceu:centrifuge", null, "Centrifuge",
                List.of(ResourcePort.input("in_slurry", ResourceKey.fluid("gtceu:slurry"), 1000)),
                List.of(
                        ResourcePort.output("out_prod", ResourceKey.item("gtceu:iron_dust"), 1),
                        ResourcePort.output("out_coolant", ResourceKey.fluid("gtceu:coolant"), 100)
                ),
                32, 20, 1, null, 0, false, false, 1, 1.0, "out_prod", null
        );

        PlanGraph graph = PlanGraph.empty("Converging + Virtual Loop")
                .withNode(producerA)
                .withNode(producerB)
                .withNode(consumer)
                .withEdge(new PlanEdge("e_a", "pa", "out_mix", "cons", "in_slurry", ResourceKey.fluid("gtceu:slurry"), true))
                .withEdge(new PlanEdge("e_b", "pb", "out_mix", "cons", "in_slurry", ResourceKey.fluid("gtceu:slurry"), true))
                .withEdge(new PlanEdge("e_loop", "cons", "out_coolant", "pa", "in_recycle", ResourceKey.fluid("gtceu:coolant"), true));

        SolveResult res = ProductionSolver.solve(graph);
        assertTrue(res.isSolved());
        assertEquals(500.0, res.edgeFlows().get("e_a"), 1e-3);
        assertEquals(500.0, res.edgeFlows().get("e_b"), 1e-3);
        assertEquals(100.0, res.edgeFlows().get("e_loop"), 1e-3);
    }

    @Test
    void coilTierAndParallelCountScaleOverclockAndMachineBom() {
        // EBF recipe: 1800K base, 120 EU/t (MV), 200 ticks, upgraded to IV (tier 5, 3 OC steps)
        // With Tritanium coil (10800K => excess 9000K => 10x 900K discounts, 5x 1800K perfect OCs -> all 3 OC steps become perfect!)
        RecipeNode ebfBase = new RecipeNode(
                "ebf", 0, 0, 0, 0, null,
                "gtceu:electric_blast_furnace/titanium", "gtceu:electric_blast_furnace", null, "EBF Titanium",
                List.of(ResourcePort.input("in", ResourceKey.item("gtceu:titanium_dust"), 1)),
                List.of(ResourcePort.output("out", ResourceKey.item("gtceu:titanium_ingot"), 1)),
                120, 200, 5, null, 1800, false, false, 1, 16.0, "out", null
        );
        OverclockCalculator.OverclockSpec noCoil = OverclockCalculator.compute(ebfBase);
        RecipeNode ebfTritaniumParallel = ebfBase.withCoilAndParallel("tritanium", 16);
        OverclockCalculator.OverclockSpec withCoilParallel = OverclockCalculator.compute(ebfTritaniumParallel);

        assertTrue(withCoilParallel.craftsPerSecondPerMachine() > noCoil.craftsPerSecondPerMachine() * 16.0);
        assertEquals(16, withCoilParallel.parallelCount());

        // Pyrolyse coil speed & Cracker EU/t discount
        RecipeNode pyroCupro = new RecipeNode(
                "pyro", 0, 0, 0, 0, null, "gtceu:pyrolyse_oven/charcoal", "gtceu:pyrolyse_oven", null, "Pyrolyse",
                List.of(), List.of(ResourcePort.output("o", ResourceKey.item("minecraft:charcoal"), 1)),
                96, 160, 2, null, 0, false, false, 1, null, null, null
        ).withCoilTier("cupronickel");
        assertEquals(213, OverclockCalculator.compute(pyroCupro).effectiveTicks()); // 160 / 0.75 = 213.33 -> floor 213

        RecipeNode crackerKanthal = new RecipeNode(
                "crack", 0, 0, 0, 0, null, "gtceu:cracker/naphtha", "gtceu:cracker", null, "Cracker",
                List.of(), List.of(ResourcePort.output("o", ResourceKey.fluid("gtceu:steam_cracked_naphtha"), 1000)),
                120, 80, 2, null, 0, false, false, 1, null, null, null
        ).withCoilTier("kanthal");
        assertEquals(108L, OverclockCalculator.compute(crackerKanthal).effectiveEut()); // 120 * 0.9 = 108

        SolveResult solved = ProductionSolver.solve(PlanGraph.empty("BOM Test").withNode(ebfTritaniumParallel));
        assertTrue(solved.isSolved());
        assertFalse(solved.machineBom().isEmpty());
        SolveResult.MachineBomEntry bomEntry = solved.machineBom().get(0);
        assertEquals("tritanium", bomEntry.coilTier());
        assertEquals(16, solved.nodeStats().get("ebf").parallelCount());
        assertTrue(bomEntry.estimatedCoils() > 0);
        assertTrue(bomEntry.parallelHatches() > 0);
    }
}
