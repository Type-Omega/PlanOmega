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
        @Nullable String targetPortId,
        NodeRole role
) implements ProcessNode {

    /**
     * Backward-compatible constructor defaulting {@code role} to {@link NodeRole#PROCESS}.
     */
    public VirtualRecipeNode(
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
    ) {
        this(id, x, y, width, height, groupId, baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier, circuit, coilTemp, cleanroom,
                perfectOverclock, notes, targetRate, targetPortId, NodeRole.PROCESS);
    }

    public VirtualRecipeNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(recipeTypeId, "recipeTypeId");
        Objects.requireNonNull(title, "title");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        notes = notes == null ? "" : notes;
        if (role == null) {
            role = NodeRole.PROCESS;
        }
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
                null,
                NodeRole.PROCESS
        );
    }

    public static VirtualRecipeNode createSource(
            String id,
            double x,
            double y,
            String title,
            ResourceKey resource,
            double amountPerCraft
    ) {
        Objects.requireNonNull(resource, "resource");
        String portId = "out_0";
        List<ResourcePort> outputs = List.of(new ResourcePort(
                portId, PortDirection.OUTPUT, resource, Math.max(0.0, amountPerCraft), 1.0, 0.0, false, null
        ));
        return new VirtualRecipeNode(
                id,
                x,
                y,
                DEFAULT_WIDTH,
                ProcessNode.preferredHeight(0, outputs.size()),
                null,
                null,
                "planomega:source",
                null,
                title,
                List.of(),
                outputs,
                0L,
                20,
                0,
                null,
                0,
                false,
                false,
                "",
                null,
                null,
                NodeRole.SOURCE
        );
    }

    public static VirtualRecipeNode createSink(
            String id,
            double x,
            double y,
            String title,
            ResourceKey resource,
            double amountPerCraft,
            @Nullable Double targetRate
    ) {
        Objects.requireNonNull(resource, "resource");
        String portId = "in_0";
        List<ResourcePort> inputs = List.of(new ResourcePort(
                portId, PortDirection.INPUT, resource, Math.max(0.0, amountPerCraft), 1.0, 0.0, false, null
        ));
        return new VirtualRecipeNode(
                id,
                x,
                y,
                DEFAULT_WIDTH,
                ProcessNode.preferredHeight(inputs.size(), 0),
                null,
                null,
                "planomega:sink",
                null,
                title,
                inputs,
                List.of(),
                0L,
                20,
                0,
                null,
                0,
                false,
                false,
                "",
                targetRate,
                portId,
                NodeRole.SINK
        );
    }

    @Override
    public VirtualRecipeNode withPosition(double x, double y) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId, role
        );
    }

    @Override
    public VirtualRecipeNode withSize(double width, double height) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId, role
        );
    }

    @Override
    public VirtualRecipeNode withGroupId(@Nullable String groupId) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId, role
        );
    }

    public VirtualRecipeNode withRole(NodeRole newRole) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId, newRole == null ? NodeRole.PROCESS : newRole
        );
    }

    public VirtualRecipeNode withTarget(@Nullable Double targetRate, @Nullable String targetPortId) {
        return new VirtualRecipeNode(
                id, x, y, width, height, groupId,
                baseRecipeId, recipeTypeId, machineId, title,
                inputs, outputs, eut, durationTicks, tier,
                circuit, coilTemp, cleanroom, perfectOverclock,
                notes, targetRate, targetPortId, role
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
                targetPortId,
                role
        );
    }
}
