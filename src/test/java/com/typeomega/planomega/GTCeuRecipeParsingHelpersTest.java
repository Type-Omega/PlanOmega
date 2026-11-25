package com.typeomega.planomega;

import com.google.gson.JsonParser;
import com.typeomega.planomega.integration.ae2.Ae2Bridge;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.recipe.GTCeuRecipeParsingHelpers;
import com.typeomega.planomega.recipe.GTCeuRecipeParsingHelpers.ChanceInput;
import com.typeomega.planomega.recipe.GTCeuRecipeParsingHelpers.ChanceMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTCeuRecipeParsingHelpersTest {

    @Test
    void catalystDetectionAndChanceModesWorkCorrectly() {
        // Non-consumable fluid or item input has chance == 0
        assertTrue(GTCeuRecipeParsingHelpers.isCatalystInput(PortDirection.INPUT, 0));
        assertFalse(GTCeuRecipeParsingHelpers.isCatalystInput(PortDirection.INPUT, 10000));
        assertFalse(GTCeuRecipeParsingHelpers.isCatalystInput(PortDirection.OUTPUT, 0));

        assertEquals(0.05, GTCeuRecipeParsingHelpers.computeTierChanceBoost(500, 10000), 1e-6);

        List<ChanceInput> chanced = List.of(
                new ChanceInput(6000, 10000, 0),
                new ChanceInput(6000, 10000, 0)
        );
        double[] xor = GTCeuRecipeParsingHelpers.computeEffectiveChances(chanced, ChanceMode.XOR);
        assertEquals(0.5, xor[0], 1e-6);
        assertEquals(0.5, xor[1], 1e-6);

        double[] first = GTCeuRecipeParsingHelpers.computeEffectiveChances(chanced, ChanceMode.FIRST);
        assertEquals(0.6, first[0], 1e-6);
        assertEquals(0.24, first[1], 1e-6);

        double[] none = GTCeuRecipeParsingHelpers.computeEffectiveChances(chanced, ChanceMode.NONE);
        assertEquals(1.0, none[0], 1e-6);
        assertEquals(1.0, none[1], 1e-6);
    }

    @Test
    void extractTagFromNestedJsonSupportsWrappersAndSingleChildArrays() {
        assertEquals("forge:ingots/steel", GTCeuRecipeParsingHelpers.extractTagFromJson(
                JsonParser.parseString("{\"tag\":\"#forge:ingots/steel\"}")));
        assertEquals("forge:dusts/iron", GTCeuRecipeParsingHelpers.extractTagFromJson(
                JsonParser.parseString("{\"ingredient\":{\"base\":{\"tag\":\"forge:dusts/iron\"}}}")));
        assertEquals("forge:plates/copper", GTCeuRecipeParsingHelpers.extractTagFromJson(
                JsonParser.parseString("{\"children\":[{\"value\":{\"tag\":\"forge:plates/copper\"}}]}")));
        assertNull(GTCeuRecipeParsingHelpers.extractTagFromJson(
                JsonParser.parseString("{\"item\":\"minecraft:iron_ingot\"}")));
    }

    @Test
    void fusionClassificationAndExtendedSearchBlobCoverResearchAndAssemblyLine() {
        assertEquals(0, GTCeuRecipeParsingHelpers.classifyFusionMk(0));
        assertEquals(1, GTCeuRecipeParsingHelpers.classifyFusionMk(120_000_000L));
        assertEquals(2, GTCeuRecipeParsingHelpers.classifyFusionMk(300_000_000L));
        assertEquals(3, GTCeuRecipeParsingHelpers.classifyFusionMk(600_000_000L));
        assertEquals(6, GTCeuRecipeParsingHelpers.computeFusionMinTier(120_000_000L));
        assertEquals(7, GTCeuRecipeParsingHelpers.computeFusionMinTier(300_000_000L));
        assertEquals(8, GTCeuRecipeParsingHelpers.computeFusionMinTier(600_000_000L));

        assertEquals(-512L, GTCeuRecipeParsingHelpers.resolveSignedEut(0L, 512L));
        assertEquals(1920L, GTCeuRecipeParsingHelpers.resolveSignedEut(1920L, 0L));

        String blob = GTCeuRecipeParsingHelpers.buildExtendedSearchBlob(
                "gtceu:assembly_line/wetware",
                List.of("wetware_mainframe"),
                300_000_000L,
                true,
                true
        );
        assertTrue(blob.contains("research:wetware_mainframe"));
        assertTrue(blob.contains("fusion mk2"));
        assertTrue(blob.contains("assembly_line assline"));
        assertTrue(blob.contains("catalyst not_consumable"));
    }

    @Test
    void ae2BridgeIsCrashSafeWithoutModAndSnapshotTracksAutocrafting() {
        assertFalse(Ae2Bridge.isAvailable());
        Ae2Bridge.Ae2NetworkSnapshot snap = new Ae2Bridge.Ae2NetworkSnapshot(
                Map.of("item:minecraft:iron_ingot", 1024L),
                Set.of("item:gtceu:steel_ingot"),
                "Test",
                1000L
        );
        Ae2Bridge.Ae2ResourceStatus iron = snap.statusOf(ResourceKey.item("minecraft:iron_ingot"));
        assertEquals(1024L, iron.storedAmount());
        assertFalse(iron.craftable());
        assertTrue(iron.isAvailable());

        Ae2Bridge.Ae2ResourceStatus steel = snap.statusOf(ResourceKey.item("gtceu:steel_ingot"));
        assertEquals(0L, steel.storedAmount());
        assertTrue(steel.craftable());
        assertTrue(steel.isAvailable());
    }
}
