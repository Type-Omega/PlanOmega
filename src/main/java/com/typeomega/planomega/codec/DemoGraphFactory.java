package com.typeomega.planomega.codec;

import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.VirtualRecipeNode;

import java.util.List;

/**
 * Provides a clean sample production chain (Sulfuric Acid + Sandbox What-If step)
 * for exercising canvas navigation, wiring, grouping, and serialization in Phase 1.
 */
public final class DemoGraphFactory {

    private DemoGraphFactory() {
    }

    public static PlanGraph createSampleSulfuricAcidPlan() {
        RecipeNode so2 = new RecipeNode(
                "node_so2", -280, -90, 0, 0, null,
                "gtceu:chemical_reactor/sulfur_dioxide_from_sulfur",
                "gtceu:chemical_reactor",
                "gtceu:lv_chemical_reactor",
                "Sulfur Dioxide (CR)",
                List.of(
                        ResourcePort.input("in_s", ResourceKey.item("gtceu:sulfur_dust"), 1),
                        ResourcePort.input("in_o2", ResourceKey.fluid("gtceu:oxygen"), 2000)
                ),
                List.of(
                        ResourcePort.output("out_so2", ResourceKey.fluid("gtceu:sulfur_dioxide"), 1000)
                ),
                7, 60, 0, 1, 0, false, false, 1, null, null, null
        );

        RecipeNode so3 = new RecipeNode(
                "node_so3", -10, -90, 0, 0, null,
                "gtceu:chemical_reactor/sulfur_trioxide",
                "gtceu:chemical_reactor",
                "gtceu:lv_chemical_reactor",
                "Sulfur Trioxide (CR)",
                List.of(
                        ResourcePort.input("in_so2", ResourceKey.fluid("gtceu:sulfur_dioxide"), 1000),
                        ResourcePort.input("in_o2", ResourceKey.fluid("gtceu:oxygen"), 1000)
                ),
                List.of(
                        ResourcePort.output("out_so3", ResourceKey.fluid("gtceu:sulfur_trioxide"), 1000)
                ),
                7, 200, 0, null, 0, false, false, 1, null, null, null
        );

        RecipeNode h2so4 = new RecipeNode(
                "node_h2so4", 260, -90, 0, 0, null,
                "gtceu:chemical_reactor/sulfuric_acid",
                "gtceu:chemical_reactor",
                "gtceu:lv_chemical_reactor",
                "Sulfuric Acid (CR)",
                List.of(
                        ResourcePort.input("in_so3", ResourceKey.fluid("gtceu:sulfur_trioxide"), 1000),
                        ResourcePort.input("in_h2o", ResourceKey.fluid("minecraft:water"), 1000)
                ),
                List.of(
                        ResourcePort.output("out_h2so4", ResourceKey.fluid("gtceu:sulfuric_acid"), 1000)
                ),
                7, 160, 0, null, 0, false, false, 1, 1000.0, "out_h2so4", null
        );

        VirtualRecipeNode contactProcess = new VirtualRecipeNode(
                "node_contact_sandbox", -10, 80, 0, 0, null,
                "gtceu:chemical_reactor/sulfur_trioxide",
                "gtceu:large_chemical_reactor",
                "gtceu:large_chemical_reactor",
                "V2O5 Contact Catalysis (What-If)",
                List.of(
                        ResourcePort.input("in_so2", ResourceKey.fluid("gtceu:sulfur_dioxide"), 1000),
                        ResourcePort.input("in_o2", ResourceKey.fluid("gtceu:oxygen"), 1000),
                        ResourcePort.catalyst("cat_v2o5", ResourceKey.item("typeomega:vanadium_pentoxide_catalyst"), 1)
                ),
                List.of(
                        ResourcePort.output("out_so3", ResourceKey.fluid("gtceu:sulfur_trioxide"), 1000)
                ),
                30, 40, 1, 12, 0, false, true,
                "Sandbox route testing faster V2O5 catalytic oxidation.",
                null, null
        );

        StickyNoteNode note = new StickyNoteNode(
                "note_intro", 260, 80, 210, 105, null,
                "Plan Omega — Fase 1",
                "Middle/Right-drag: Pan\nScroll: Zoom\nDrag ports: Wire\nCtrl+G: Group",
                StickyNoteNode.DEFAULT_COLOR
        );

        PlanGraph graph = PlanGraph.empty("Sulfuric Acid Demo")
                .withNode(so2)
                .withNode(so3)
                .withNode(h2so4)
                .withNode(contactProcess)
                .withNode(note)
                .groupNodes("Contact Acid Chain", List.of("node_so2", "node_so3", "node_h2so4"), GroupNode.DEFAULT_COLOR)
                .withEdge(new PlanEdge(
                        "edge_so2_so3",
                        "node_so2", "out_so2",
                        "node_so3", "in_so2",
                        ResourceKey.fluid("gtceu:sulfur_dioxide"),
                        false
                ))
                .withEdge(new PlanEdge(
                        "edge_so3_h2so4",
                        "node_so3", "out_so3",
                        "node_h2so4", "in_so3",
                        ResourceKey.fluid("gtceu:sulfur_trioxide"),
                        false
                ));
        return graph;
    }
}
