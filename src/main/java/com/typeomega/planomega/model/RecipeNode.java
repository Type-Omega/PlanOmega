package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable node backed by a concrete in-game recipe (GTCEu, vanilla, KubeJS, GTNA, TypeOmega, etc.).
 */
public record RecipeNode(
        String id,
        double x,
        double y,
        double width,
        double height,
        @Nullable String groupId,
        String recipeId,
        String recipeTypeId,
        @Nullable String machineId,
        String title,
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
        @Nullable Double targetRate,
        @Nullable String targetPortId,
        @Nullable Double lockedRate
) implements ProcessNode {

    public RecipeNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(title, "title");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        if (width <= 0) {
            width = DEFAULT_WIDTH;
        }
        if (height <= 0) {
            height = ProcessNode.preferredHeight(inputs.size(), outputs.size());
        }
        if (durationTicks <= 0) {
            durationTicks = 1;
        }
        if (parallelLimit <= 0) {
            parallelLimit = 1;
        }
    }

    @Override
    public RecipeNode withPosition(double x, double y) {
        return new RecipeNode(
                id, x, y, width, height, groupId,
                recipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                parallelLimit, targetRate, targetPortId, lockedRate
        );
    }

    @Override
    public RecipeNode withSize(double width, double height) {
        return new RecipeNode(
                id, x, y, width, height, groupId,
                recipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                parallelLimit, targetRate, targetPortId, lockedRate
        );
    }

    @Override
    public RecipeNode withGroupId(@Nullable String groupId) {
        return new RecipeNode(
                id, x, y, width, height, groupId,
                recipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                parallelLimit, targetRate, targetPortId, lockedRate
        );
    }

    public RecipeNode withTarget(@Nullable Double targetRate, @Nullable String targetPortId) {
        return new RecipeNode(
                id, x, y, width, height, groupId,
                recipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                parallelLimit, targetRate, targetPortId, lockedRate
        );
    }
}
