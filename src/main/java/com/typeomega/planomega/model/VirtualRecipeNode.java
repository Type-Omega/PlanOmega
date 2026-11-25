package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Editable "What-If" / Sandbox recipe node used to prototype chemical routes or tweak existing recipes
 * before exporting diffs for KubeJS or TypeOmega Java code.
 */
public record VirtualRecipeNode(
        String id,
        double x,
        double y,
        double width,
        double height,
        @Nullable String groupId,
        @Nullable String baseRecipeId,
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
        String notes,
        @Nullable Double targetRate,
        @Nullable String targetPortId
) implements ProcessNode {

    public VirtualRecipeNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(title, "title");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        notes = notes == null ? "" : notes;
        if (width <= 0) {
            width = DEFAULT_WIDTH;
        }
        if (height <= 0) {
            height = ProcessNode.preferredHeight(inputs.size(), outputs.size());
        }
        if (durationTicks <= 0) {
            durationTicks = 1;
        }
        if (baseRecipeId != null && baseRecipeId.isBlank()) {
            baseRecipeId = null;
        }
    }

    public static VirtualRecipeNode createDraft(
            String id,
            double x,
            double y,
            String title,
            String recipeTypeId,
            List<ResourcePort> inputs,
            List<ResourcePort> outputs,
            long eut,
            int durationTicks
    ) {
        return new VirtualRecipeNode(
                id,
                x,
                y,
                DEFAULT_WIDTH,
                ProcessNode.preferredHeight(inputs.size(), outputs.size()),
                null,
                null,
                recipeTypeId,
                null,
                title,
                inputs,
                outputs,
                eut,
                durationTicks,
                VoltageTier.fromEut(eut).index(),
                null,
                0,
                false,
                false,
                "",
                null,
                null
        );
    }

    @Override
    public VirtualRecipeNode withPosition(double x, double y) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId
        );
    }

    @Override
    public VirtualRecipeNode withSize(double width, double height) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId
        );
    }

    @Override
    public VirtualRecipeNode withGroupId(@Nullable String groupId) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId
        );
    }

    public VirtualRecipeNode withEditedSpec(
            String title,
            String recipeTypeId,
            List<ResourcePort> inputs,
            List<ResourcePort> outputs,
            long eut,
            int durationTicks,
            int tier,
            @Nullable Integer circuit,
            int coilTemp,
            boolean cleanroom,
            boolean perfectOverclock,
            String notes
    ) {
        return new VirtualRecipeNode(
                id,
                x,
                y,
                Math.max(width, DEFAULT_WIDTH),
                ProcessNode.preferredHeight(inputs.size(), outputs.size()),
                groupId,
                baseRecipeId,
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
                notes,
                targetRate,
                targetPortId
        );
    }
}
