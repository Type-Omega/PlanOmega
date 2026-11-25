package com.typeomega.planomega;

import com.typeomega.planomega.client.storage.PlanStorage;
import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.codec.GroupTemplateCodec;
import com.typeomega.planomega.codec.PlanCodec;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.VirtualRecipeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCodecTest {

    @Test
    void jsonAndShareStringRoundTripPreservesAllNodeTypes() throws Exception {
        PlanGraph original = DemoGraphFactory.createSampleSulfuricAcidPlan();
        String json = PlanCodec.toJson(original);
        PlanGraph fromJson = PlanCodec.fromJson(json);

        assertEquals(original, fromJson);
        assertInstanceOf(RecipeNode.class, fromJson.nodes().get("node_so2"));
        assertInstanceOf(VirtualRecipeNode.class, fromJson.nodes().get("node_contact_sandbox"));
        assertInstanceOf(StickyNoteNode.class, fromJson.nodes().get("note_intro"));

        String share = PlanCodec.encodeShareString(original);
        assertTrue(share.startsWith(PlanCodec.SHARE_PREFIX));
        PlanGraph fromShare = PlanCodec.decodeShareString(share);
        assertEquals(original, fromShare);
    }

    @Test
    void invalidShareHeaderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decodeShareString("INVALID:abc"));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decodeShareString(null));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decodeShareString(PlanCodec.SHARE_PREFIX + "!!!not-base64!!!"));
        String notGzip = PlanCodec.SHARE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString("plain-text".getBytes());
        assertThrows(IOException.class, () -> PlanCodec.decodeShareString(notGzip));
    }

    @Test
    void corruptedJsonAndMissingFieldsAreHandledSafely() {
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.fromJson("{not valid json"));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.fromJson("[]"));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.fromJson("   "));

        PlanGraph minimal = PlanCodec.fromJson("{\"schemaVersion\":1,\"nodes\":[{\"type\":\"recipe\",\"id\":\"n1\",\"inputs\":null,\"outputs\":null}]}");
        assertEquals(1, minimal.schemaVersion());
        assertEquals("Untitled Plan", minimal.name());
        assertInstanceOf(RecipeNode.class, minimal.nodes().get("n1"));
    }

    @Test
    void planStorageLoadAndDeleteWorkWithSpecialFilenames(@TempDir Path tempDir) throws IOException {
        PlanGraph graph = DemoGraphFactory.createSampleSulfuricAcidPlan().withName("My Plan / #1 (Test)!");
        Path file = tempDir.resolve("my_plan_1_test" + PlanStorage.FILE_EXT);
        Files.writeString(file, PlanCodec.toJson(graph));

        PlanGraph loaded = PlanStorage.loadPlan(file);
        assertEquals(graph, loaded);
        assertTrue(PlanStorage.deletePlan(file));
        assertFalse(PlanStorage.deletePlan(file));
    }

    @Test
    void schemaVersionBackwardCompatibilityAndV2CoilParallelRoundTrip() {
        // Legacy v1 JSON without coilTier / parallelCount defaults safely
        String v1Json = """
                {
                  "schemaVersion": 1,
                  "name": "Legacy V1",
                  "nodes": [
                    {
                      "type": "recipe",
                      "id": "n1",
                      "recipeId": "gtceu:electric_blast_furnace/steel",
                      "recipeTypeId": "gtceu:electric_blast_furnace",
                      "title": "Steel",
                      "eut": 120,
                      "durationTicks": 200,
                      "tier": 2,
                      "coilTemp": 1800,
                      "inputs": [],
                      "outputs": []
                    }
                  ],
                  "edges": []
                }
                """;
        PlanGraph v1 = PlanCodec.fromJson(v1Json);
        assertEquals(1, v1.schemaVersion());
        RecipeNode v1Node = (RecipeNode) v1.nodes().get("n1");
        assertEquals(null, v1Node.coilTier());
        assertEquals(1, v1Node.parallelCount());

        // V2 round-trip preserves coilTier and parallelCount
        RecipeNode upgraded = v1Node.withCoilAndParallel("kanthal", 16);
        PlanGraph v2 = PlanGraph.empty("V2 Plan").withNode(upgraded);
        String v2Json = PlanCodec.toJson(v2);
        PlanGraph roundTrip = PlanCodec.fromJson(v2Json);
        assertEquals(PlanCodec.CODEC_SCHEMA_VERSION, roundTrip.schemaVersion());
        RecipeNode rtNode = (RecipeNode) roundTrip.nodes().get("n1");
        assertEquals("kanthal", rtNode.coilTier());
        assertEquals(16, rtNode.parallelCount());
    }

    @Test
    void groupTemplateCodecAndStorageRoundTripAndInstantiate(@TempDir Path tempDir) throws IOException {
        PlanGraph sample = DemoGraphFactory.createSampleSulfuricAcidPlan();
        PlanGraph sub = sample.extractSubgraph(java.util.List.of("node_so2", "node_so3"));
        GroupNode grp = new GroupNode("grp_acid", 0, 0, 400, 200, null, "Acid Core", 0, false, java.util.List.of("node_so2", "node_so3"));
        PlanGraph templateGraph = sub.withNode(grp);

        String json = GroupTemplateCodec.toJson("Acid Core Template", "SO2 -> SO3", templateGraph);
        GroupTemplateCodec.GroupTemplate parsed = GroupTemplateCodec.fromJson(json);
        assertEquals("Acid Core Template", parsed.name());
        assertEquals("SO2 -> SO3", parsed.description());
        assertEquals(3, parsed.subgraph().nodes().size());

        PlanGraph instantiated = GroupTemplateCodec.instantiate(parsed, 500.0, 300.0);
        assertEquals(3, instantiated.nodes().size());
        assertFalse(instantiated.nodes().containsKey("grp_acid"));

        Path saved = tempDir.resolve("acid_core" + GroupTemplateCodec.TEMPLATE_EXT);
        Files.writeString(saved, json);
        GroupTemplateCodec.GroupTemplate loaded = PlanStorage.loadGroupTemplate(saved);
        assertEquals("Acid Core Template", loaded.name());
        assertTrue(PlanStorage.deleteGroupTemplate(saved));
    }
}
