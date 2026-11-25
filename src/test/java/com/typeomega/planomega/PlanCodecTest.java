package com.typeomega.planomega;

import com.typeomega.planomega.client.storage.PlanStorage;
import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.codec.PlanCodec;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
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
}
