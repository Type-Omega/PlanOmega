package com.typeomega.planomega.solver;

import com.typeomega.planomega.model.ResourceKey;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable output of {@link ProductionSolver} containing per-node machine counts,
 * per-edge flow rates, net raw material imports, surplus/final exports, and machine BOM.
 */
public record SolveResult(
        Status status,
        Map<String, NodeSolveStats> nodeStats,
        Map<String, Double> edgeFlows,
        Map<ResourceKey, Double> netImports,
        Map<ResourceKey, Double> netExports,
        double totalConsumptionEut,
        double totalGenerationEut,
        int totalMachinesCeil,
        @Nullable String message,
        List<MachineBomEntry> machineBom
) {

    public SolveResult(
            Status status,
            Map<String, NodeSolveStats> nodeStats,
            Map<String, Double> edgeFlows,
            Map<ResourceKey, Double> netImports,
            Map<ResourceKey, Double> netExports,
            double totalConsumptionEut,
            double totalGenerationEut,
            int totalMachinesCeil,
            @Nullable String message
    ) {
        this(status, nodeStats, edgeFlows, netImports, netExports,
                totalConsumptionEut, totalGenerationEut, totalMachinesCeil, message, List.of());
    }

    public enum Status {
        OPTIMAL,
        FEASIBLE,
        INFEASIBLE,
        NO_TARGETS,
        ERROR
    }

    public static final SolveResult NO_TARGETS = new SolveResult(
            Status.NO_TARGETS,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            0.0,
            0.0,
            0,
            null,
            List.of()
    );

    public SolveResult {
        nodeStats = nodeStats == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(nodeStats));
        edgeFlows = edgeFlows == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(edgeFlows));
        netImports = netImports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(netImports));
        netExports = netExports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(netExports));
        machineBom = machineBom == null ? List.of() : List.copyOf(machineBom);
    }

    public double netEut() {
        return totalConsumptionEut - totalGenerationEut;
    }

    public boolean isSolved() {
        return status == Status.OPTIMAL || status == Status.FEASIBLE;
    }

    public record NodeSolveStats(
            String nodeId,
            double craftsPerSecond,
            double machinesExact,
            int machinesCeil,
            long effectiveEutPerMachine,
            double totalEut,
            double peakEut,
            int effectiveTicks,
            int subtickParallels,
            int machineTier,
            int parallelCount
    ) {
        public NodeSolveStats(
                String nodeId,
                double craftsPerSecond,
                double machinesExact,
                int machinesCeil,
                long effectiveEutPerMachine,
                double totalEut,
                double peakEut,
                int effectiveTicks,
                int subtickParallels,
                int machineTier
        ) {
            this(nodeId, craftsPerSecond, machinesExact, machinesCeil, effectiveEutPerMachine,
                    totalEut, peakEut, effectiveTicks, subtickParallels, machineTier, 1);
        }
    }

    /**
     * Aggregated Bill-of-Materials entry for machines, hatches, coils, and parallel hatches.
     */
    public record MachineBomEntry(
            String machineKey,
            String displayName,
            int tier,
            int machineCount,
            boolean multiblock,
            int inputBuses,
            int outputBuses,
            int inputHatches,
            int outputHatches,
            int energyHatches,
            @Nullable String coilTier,
            int estimatedCoils,
            int parallelHatches
    ) {
    }
}
