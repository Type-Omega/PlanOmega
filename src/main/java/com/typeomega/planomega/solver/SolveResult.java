package com.typeomega.planomega.solver;

import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.VoltageTier;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable output of {@link ProductionSolver} containing per-node machine counts,
 * per-edge flow rates, net raw material imports, surplus/final exports, machine BOM,
 * and per-node / per-tier electrical amperage.
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
        List<MachineBomEntry> machineBom,
        Map<VoltageTier, Double> tierAmperage
) {

    private static final DecimalFormat AMP_FMT = new DecimalFormat("#.##");

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

    public SolveResult(
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
        this(status, nodeStats, edgeFlows, netImports, netExports,
                totalConsumptionEut, totalGenerationEut, totalMachinesCeil, message,
                machineBom, aggregateTierAmperage(nodeStats));
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
            List.of(),
            Map.of()
    );

    public SolveResult {
        nodeStats = nodeStats == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(nodeStats));
        edgeFlows = edgeFlows == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(edgeFlows));
        netImports = netImports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(netImports));
        netExports = netExports == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(netExports));
        machineBom = machineBom == null ? List.of() : List.copyOf(machineBom);
        tierAmperage = tierAmperage == null || tierAmperage.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(tierAmperage));
    }

    public static String recommendEnergyHatch(double amps) {
        double abs = Math.abs(amps);
        if (abs <= 1e-6) {
            return "None";
        }
        if (abs <= 2.0 + 1e-6) {
            return "1x 2A Energy Hatch";
        }
        if (abs <= 4.0 + 1e-6) {
            return "1x 4A Energy Hatch";
        }
        if (abs <= 16.0 + 1e-6) {
            return "1x 16A Energy Hatch";
        }
        int hatches16A = (int) Math.ceil((abs - 1e-6) / 16.0);
        return hatches16A + "x 16A Energy Hatch";
    }

    public static String formatTierAmperage(VoltageTier tier, double amps) {
        return AMP_FMT.format(amps) + "A " + tier.shortName() + " -> " + recommendEnergyHatch(amps);
    }

    private static Map<VoltageTier, Double> aggregateTierAmperage(@Nullable Map<String, NodeSolveStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return Map.of();
        }
        EnumMap<VoltageTier, Double> byTier = new EnumMap<>(VoltageTier.class);
        for (NodeSolveStats s : stats.values()) {
            if (s.amps() > 1e-6) {
                VoltageTier tier = VoltageTier.byIndex(s.machineTier());
                byTier.merge(tier, s.amps(), Double::sum);
            }
        }
        return byTier.isEmpty() ? Map.of() : byTier;
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
            int parallelCount,
            double amps
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
                int machineTier,
                int parallelCount
        ) {
            this(nodeId, craftsPerSecond, machinesExact, machinesCeil, effectiveEutPerMachine,
                    totalEut, peakEut, effectiveTicks, subtickParallels, machineTier, parallelCount,
                    computeAmps(totalEut, machineTier));
        }

        public static double computeAmps(double eut, int machineTier) {
            long tierVoltage = Math.max(1L, VoltageTier.byIndex(machineTier).voltage());
            return eut / tierVoltage;
        }

        public double peakAmps() {
            return computeAmps(peakEut, machineTier);
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
