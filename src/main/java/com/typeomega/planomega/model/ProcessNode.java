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

    List<Integer> PARALLEL_OPTIONS = List.of(1, 4, 16, 64, 256);

    /**
     * GTCEu Modern heating coil tiers for EBF, Pyrolyse Oven, and Cracker multiblocks.
     */
    enum CoilTier {
        CUPRONICKEL("cupronickel", "Cupronickel", 1800, 0),
        KANTHAL("kanthal", "Kanthal", 2700, 1),
        NICHROME("nichrome", "Nichrome", 3600, 2),
        RTM_ALLOY("rtm_alloy", "RTM Alloy", 4500, 3),
        HSS_G("hss_g", "HSS-G", 5400, 4),
        NAQUADAH("naquadah", "Naquadah", 7200, 5),
        TRINIUM("trinium", "Trinium", 9001, 6),
        TRITANIUM("tritanium", "Tritanium", 10800, 7);

        private final String id;
        private final String displayName;
        private final int temperatureK;
        private final int coilLevel;

        CoilTier(String id, String displayName, int temperatureK, int coilLevel) {
            this.id = id;
            this.displayName = displayName;
            this.temperatureK = temperatureK;
            this.coilLevel = coilLevel;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public int temperatureK() {
            return temperatureK;
        }

        public int coilLevel() {
            return coilLevel;
        }

        public static @Nullable CoilTier fromId(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String norm = raw.trim().toLowerCase(java.util.Locale.ROOT);
            for (CoilTier tier : values()) {
                if (tier.id.equals(norm) || tier.name().equalsIgnoreCase(norm)) {
                    return tier;
                }
            }
            return null;
        }
    }

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

    @Nullable default String coilTier() {
        return null;
    }

    default int parallelCount() {
        return 1;
    }

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
