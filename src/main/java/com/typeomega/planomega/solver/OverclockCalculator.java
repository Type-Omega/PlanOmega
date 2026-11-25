package com.typeomega.planomega.solver;

import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ProcessNode.CoilTier;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VoltageTier;

import java.util.Locale;

/**
 * Implements GTCEu Modern (7.5.3) overclocking, sub-tick parallelization,
 * and tier-boosted chance calculations for {@link ProcessNode}s.
 */
public final class OverclockCalculator {

    private OverclockCalculator() {
    }

    public record OverclockSpec(
            int recipeTier,
            int machineTier,
            int ocSteps,
            long effectiveEut,
            int effectiveTicks,
            int subtickParallels,
            double craftsPerSecondPerMachine,
            int parallelCount
    ) {
        public OverclockSpec(
                int recipeTier,
                int machineTier,
                int ocSteps,
                long effectiveEut,
                int effectiveTicks,
                int subtickParallels,
                double craftsPerSecondPerMachine
        ) {
            this(recipeTier, machineTier, ocSteps, effectiveEut, effectiveTicks,
                    subtickParallels, craftsPerSecondPerMachine, 1);
        }

        public double secondsPerCraftPerMachine() {
            return effectiveTicks / (20.0 * subtickParallels * Math.max(1, parallelCount));
        }
    }

    public static OverclockSpec compute(ProcessNode node) {
        long baseEut = node.eut();
        int baseTicks = Math.max(1, node.durationTicks());
        int recipeTier = VoltageTier.fromEut(baseEut).index();
        int machineTier = Math.max(recipeTier, node.tier());
        int ocSteps = (baseEut <= 0) ? 0 : Math.max(0, machineTier - recipeTier);
        int parallelCount = Math.max(1, node.parallelCount());

        CoilTier coil = CoilTier.fromId(node.coilTier());
        String typeLower = node.recipeTypeId() == null ? "" : node.recipeTypeId().toLowerCase(Locale.ROOT);

        double coilSpeedMultiplier = 1.0;
        double coilEutDiscount = 1.0;
        int coilPerfectOcs = 0;

        if (coil != null) {
            if (typeLower.contains("pyrolyse")) {
                coilSpeedMultiplier = (coil.coilLevel() == 0) ? 0.75 : (coil.coilLevel() + 1) / 2.0;
            } else if (typeLower.contains("cracker")) {
                coilEutDiscount = Math.max(0.1, 1.0 - 0.1 * coil.coilLevel());
            } else if (node.coilTemp() > 0 || typeLower.contains("blast_furnace") || typeLower.contains("ebf")) {
                int reqTemp = Math.max(1800, node.coilTemp());
                int excess = Math.max(0, coil.temperatureK() - reqTemp);
                coilEutDiscount = Math.pow(0.95, excess / 900);
                coilPerfectOcs = excess / 1800;
            }
        }

        int perfectSteps = node.perfectOverclock() ? ocSteps : Math.min(ocSteps, coilPerfectOcs);
        int normalSteps = Math.max(0, ocSteps - perfectSteps);

        double speedFactor = Math.pow(4.0, perfectSteps) * Math.pow(2.0, normalSteps) * coilSpeedMultiplier;
        double eutFactor = (ocSteps > 0 ? Math.pow(4.0, ocSteps) : 1.0) * coilEutDiscount;
        long effectiveEut = Math.round(baseEut * eutFactor) * parallelCount;

        double rawTicks = baseTicks / speedFactor;
        int effectiveTicks;
        int subtickParallels;
        if (rawTicks >= 1.0) {
            effectiveTicks = Math.max(1, (int) Math.floor(rawTicks));
            subtickParallels = 1;
        } else {
            effectiveTicks = 1;
            subtickParallels = Math.max(1, (int) Math.round(1.0 / rawTicks));
        }

        double craftsPerSecondPerMachine = (20.0 * subtickParallels * parallelCount) / effectiveTicks;
        return new OverclockSpec(
                recipeTier,
                machineTier,
                ocSteps,
                effectiveEut,
                effectiveTicks,
                subtickParallels,
                craftsPerSecondPerMachine,
                parallelCount
        );
    }

    public static double expectedAmountPerCraft(ResourcePort port, int ocSteps) {
        if (port.catalyst() || port.amount() <= 0.0) {
            return 0.0;
        }
        double effectiveChance = Math.min(1.0, Math.max(0.0, port.chance() + ocSteps * port.tierChanceBoost()));
        return port.amount() * effectiveChance;
    }
}
