package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Shared contract for production-step nodes ({@link RecipeNode} and {@link VirtualRecipeNode})
 * that carry inputs, outputs, energy/duration metadata, and optional production targets.
 */
public sealed interface ProcessNode extends PlanNode permits RecipeNode, VirtualRecipeNode {

    double DEFAULT_WIDTH = 210.0;
    double HEADER_HEIGHT = 28.0;
    double META_HEIGHT = 16.0;
    double PORT_ROW_HEIGHT = 18.0;
    double FOOTER_PADDING = 10.0;

    String recipeTypeId();

    @Nullable String machineId();

    String title();

    List<ResourcePort> inputs();

    List<ResourcePort> outputs();

    long eut();

    int durationTicks();

    int tier();

    @Nullable Integer circuit();

    int coilTemp();

    boolean cleanroom();

    boolean perfectOverclock();

    @Nullable Double targetRate();

    @Nullable String targetPortId();

    default boolean isVirtual() {
        return this instanceof VirtualRecipeNode;
    }

    default Optional<ResourcePort> findPort(String portId) {
        for (ResourcePort port : inputs()) {
            if (port.id().equals(portId)) {
                return Optional.of(port);
            }
        }
        for (ResourcePort port : outputs()) {
            if (port.id().equals(portId)) {
                return Optional.of(port);
            }
        }
        return Optional.empty();
    }

    /**
     * Computes the preferred card height to accommodate all input and output rows cleanly.
     */
    static double preferredHeight(int inputCount, int outputCount) {
        int rows = Math.max(1, Math.max(inputCount, outputCount));
        return HEADER_HEIGHT + META_HEIGHT + (rows * PORT_ROW_HEIGHT) + FOOTER_PADDING;
    }
}
