package com.typeomega.planomega.recipe;

import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourcePort;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Unified, mod-agnostic recipe descriptor extracted from Vanilla, GTCEu, KubeJS, GTNA, or TypeOmega.
 */
public record RecipeEntry(
        String recipeId,
        String recipeTypeId,
        String sourceMod,
        @Nullable String machineId,
        String title,
        String machineLabel,
        List<ResourcePort> inputs,
        List<ResourcePort> outputs,
        long eut,
        int durationTicks,
        int tier,
        @Nullable Integer circuit,
        int coilTemp,
        boolean cleanroom,
        boolean perfectOverclock,
        int parallelLimit,
        String searchBlob
) {

    public RecipeEntry {
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(sourceMod, "sourceMod");
        Objects.requireNonNull(title, "title");
        machineLabel = machineLabel == null ? recipeTypeId : machineLabel;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        if (durationTicks <= 0) {
            durationTicks = 1;
        }
        if (parallelLimit <= 0) {
            parallelLimit = 1;
        }
        if (searchBlob == null || searchBlob.isEmpty()) {
            searchBlob = buildSearchBlob(recipeId, recipeTypeId, sourceMod, title, machineLabel, inputs, outputs);
        }
    }

    public RecipeNode toRecipeNode(double x, double y) {
        return new RecipeNode(
                PlanGraph.newId("rec"),
                x,
                y,
                0,
                0,
                null,
                recipeId,
                recipeTypeId,
                machineId,
                title,
                inputs,
                outputs,
                eut,
                durationTicks,
                tier,
                circuit,
                coilTemp,
                cleanroom,
                perfectOverclock,
                parallelLimit,
                null,
                null,
                null
        );
    }

    private static String buildSearchBlob(
            String recipeId,
            String recipeTypeId,
            String sourceMod,
            String title,
            String machineLabel,
            List<ResourcePort> inputs,
            List<ResourcePort> outputs
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(' ')
                .append(recipeId).append(' ')
                .append(recipeTypeId).append(' ')
                .append(machineLabel).append(' ')
                .append('@').append(sourceMod);
        for (ResourcePort p : inputs) {
            sb.append(' ').append(p.resource().id()).append(' ').append(p.displayLabel());
        }
        for (ResourcePort p : outputs) {
            sb.append(' ').append(p.resource().id()).append(' ').append(p.displayLabel());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
