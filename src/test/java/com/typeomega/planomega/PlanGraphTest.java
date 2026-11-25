package com.typeomega.planomega;

import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.UndoHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanGraphTest {

    @Test
    void groupMoveDoesNotDoubleMoveChildren() {
        PlanGraph graph = DemoGraphFactory.createSampleSulfuricAcidPlan();
        GroupNode group = graph.nodes().values().stream()
                .filter(GroupNode.class::isInstance)
                .map(GroupNode.class::cast)
                .findFirst()
                .orElseThrow();
        RecipeNode childBefore = (RecipeNode) graph.nodes().get("node_so2");

        PlanGraph moved = graph.moveNodes(List.of(group.id(), "node_so2"), 40.0, -25.0);
        RecipeNode childAfter = (RecipeNode) moved.nodes().get("node_so2");
        GroupNode groupAfter = (GroupNode) moved.nodes().get(group.id());

        assertEquals(childBefore.x() + 40.0, childAfter.x(), 1e-6);
        assertEquals(childBefore.y() - 25.0, childAfter.y(), 1e-6);
        assertEquals(group.x() + 40.0, groupAfter.x(), 1e-6);
        assertEquals(group.y() - 25.0, groupAfter.y(), 1e-6);
    }

    @Test
    void collapsingGroupHidesChildrenAndRedirectsVisibleHost() {
        PlanGraph graph = DemoGraphFactory.createSampleSulfuricAcidPlan();
        String groupId = graph.nodes().values().stream()
                .filter(GroupNode.class::isInstance)
                .map(PlanNode::id)
                .findFirst()
                .orElseThrow();

        assertFalse(graph.isHiddenByCollapsedGroup("node_so2"));
        PlanGraph collapsed = graph.toggleGroupCollapsed(groupId);
        assertTrue(collapsed.isHiddenByCollapsedGroup("node_so2"));
        assertEquals(groupId, collapsed.resolveVisibleHostId("node_so2"));
        assertFalse(collapsed.isHiddenByCollapsedGroup(groupId));
    }

    @Test
    void removingNodeCleansEdgesAndGroupReferences() {
        PlanGraph graph = DemoGraphFactory.createSampleSulfuricAcidPlan();
        PlanGraph trimmed = graph.withoutNode("node_so3");

        assertFalse(trimmed.nodes().containsKey("node_so3"));
        assertEquals(0, trimmed.edges().size());
        GroupNode group = trimmed.nodes().values().stream()
                .filter(GroupNode.class::isInstance)
                .map(GroupNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertFalse(group.childNodeIds().contains("node_so3"));
    }

    @Test
    void portCompatibilityAndDuplicateProtection() {
        RecipeNode a = new RecipeNode(
                "a", 0, 0, 0, 0, null, "r:a", "t:a", null, "A",
                List.of(),
                List.of(ResourcePort.output("out", ResourceKey.item("minecraft:iron_ingot"), 1)),
                8, 20, 0, null, 0, false, false, 1, null, null, null
        );
        RecipeNode b = new RecipeNode(
                "b", 250, 0, 0, 0, null, "r:b", "t:b", null, "B",
                List.of(
                        ResourcePort.input("in_tag", ResourceKey.tag("forge:ingots/iron"), 1),
                        ResourcePort.input("in_fluid", ResourceKey.fluid("minecraft:water"), 1000)
                ),
                List.of(),
                8, 20, 0, null, 0, false, false, 1, null, null, null
        );

        PlanGraph g = PlanGraph.empty("Test").withNode(a).withNode(b);
        assertTrue(g.canConnect("a", "out", "b", "in_tag"));
        assertFalse(g.canConnect("a", "out", "b", "in_fluid"));
        assertFalse(g.canConnect("a", "out", "a", "out"));

        PlanGraph connected = g.withEdge(new PlanEdge("e1", "a", "out", "b", "in_tag", ResourceKey.item("minecraft:iron_ingot"), true));
        assertFalse(connected.canConnect("a", "out", "b", "in_tag"));
    }

    @Test
    void undoRedoRestoresImmutableSnapshots() {
        UndoHistory history = new UndoHistory(16);
        PlanGraph g0 = PlanGraph.empty("Start");
        PlanGraph g1 = g0.withName("Renamed");
        history.recordBeforeEdit(g0);

        assertTrue(history.canUndo());
        PlanGraph undone = history.undo(g1);
        assertEquals("Start", undone.name());
        assertTrue(history.canRedo());

        PlanGraph redone = history.redo(undone);
        assertEquals("Renamed", redone.name());
        assertNull(redone.nodes().get("missing"));
    }
}
