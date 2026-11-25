package com.typeomega.planomega;

import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.recipe.RecipeCatalog;
import com.typeomega.planomega.recipe.RecipeEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCatalogTest {

    @Test
    void catalogIndexesProducersConsumersAndModQueries() {
        RecipeEntry gtAcid = new RecipeEntry(
                "gtceu:chemical_reactor/sulfuric_acid",
                "gtceu:chemical_reactor",
                "gtceu",
                "gtceu:chemical_reactor",
                "Sulfuric Acid",
                "Chemical Reactor",
                List.of(
                        ResourcePort.input("in_0", ResourceKey.fluid("gtceu:sulfur_trioxide"), 1000),
                        ResourcePort.input("in_1", ResourceKey.fluid("minecraft:water"), 1000)
                ),
                List.of(ResourcePort.output("out_0", ResourceKey.fluid("gtceu:sulfuric_acid"), 1000)),
                7, 160, 0, null, 0, false, false, 1, null
        );
        RecipeEntry vanillaBlock = new RecipeEntry(
                "minecraft:iron_block",
                "minecraft:crafting",
                "minecraft",
                null,
                "Block of Iron",
                "Crafting",
                List.of(ResourcePort.input("in_0", ResourceKey.item("minecraft:iron_ingot"), 9)),
                List.of(ResourcePort.output("out_0", ResourceKey.item("minecraft:iron_block"), 1)),
                0, 20, 0, null, 0, false, false, 1, null
        );

        RecipeCatalog catalog = new RecipeCatalog(List.of(gtAcid, vanillaBlock));

        assertEquals(1, catalog.producersOf(ResourceKey.fluid("gtceu:sulfuric_acid")).size());
        assertEquals(1, catalog.consumersOf(ResourceKey.fluid("minecraft:water")).size());
        assertEquals(1, catalog.search("@gtceu sulfuric", null, null, 10).size());
        assertEquals(1, catalog.search("@minecraft iron", null, null, 10).size());

        RecipeNode node = gtAcid.toRecipeNode(100, 200);
        assertEquals(100.0, node.x());
        assertEquals(200.0, node.y());
        assertEquals("gtceu:chemical_reactor/sulfuric_acid", node.recipeId());
    }

    @Test
    void catalogHandlesEmptyNullKeysLimitsAndTagQueries() {
        assertEquals(0, RecipeCatalog.EMPTY.size());
        assertTrue(RecipeCatalog.EMPTY.producersOf(null).isEmpty());
        assertTrue(RecipeCatalog.EMPTY.consumersOf(null).isEmpty());
        assertTrue(RecipeCatalog.EMPTY.search("anything", null, null, 10).isEmpty());

        RecipeEntry tagged = new RecipeEntry(
                "gtceu:smelter/iron",
                "gtceu:electric_furnace",
                "gtceu",
                null,
                "Iron Ingot",
                "Smelter",
                List.of(ResourcePort.input("in_0", ResourceKey.tag("forge:ores/iron"), 1)),
                List.of(ResourcePort.output("out_0", ResourceKey.item("minecraft:iron_ingot"), 1)),
                4, 100, 1, null, 0, false, false, 1, null
        );
        RecipeCatalog catalog = new RecipeCatalog(List.of(tagged));
        assertTrue(catalog.findById("gtceu:smelter/iron").isPresent());
        assertTrue(catalog.findById("missing").isEmpty());
        assertEquals(1, catalog.search("#forge:ores/iron", null, null, 5).size());
        assertEquals(1, catalog.search("", ResourceKey.item("minecraft:iron_ingot"), null, 1).size());
        assertEquals(0, catalog.search("@minecraft", null, null, 5).size());
    }
}
