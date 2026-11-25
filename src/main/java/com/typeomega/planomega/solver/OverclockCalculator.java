package com.typeomega.planomega.solver;

import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VoltageTier;

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
            double craftsPerSecondPerMachine
    ) {
        public double secondsPerCraftPerMachine() {
            return effectiveTicks / (20.0 * subtickParallels);
        }
    }

    public static OverclockSpec compute(ProcessNode node) {
        long baseEut = node.eut();
        int baseTicks = Math.max(1, node.durationTicks());
        int recipeTier = VoltageTier.fromEut(baseEut).index();
        int machineTier = Math.max(recipeTier, node.tier());
        int ocSteps = Math.max(0, machineTier - recipeTier);

        if (baseEut <= 0 || ocSteps == 0) {
            return new OverclockSpec(
                    recipeTier,
                    machineTier,
                    0,
                    baseEut,
                    baseTicks,
                    1,
                    20.0 / baseTicks
            );
        }

        double speedFactor = node.perfectOverclock() ? Math.pow(4.0, ocSteps) : Math.pow(2.0, ocSteps);
        double eutFactor = Math.pow(4.0, ocSteps);
        long effectiveEut = Math.round(baseEut * eutFactor);

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

        double craftsPerSecondPerMachine = (20.0 * subtickParallels) / effectiveTicks;
        return new OverclockSpec(
                recipeTier,
                machineTier,
                ocSteps,
                effectiveEut,
                effectiveTicks,
                subtickParallels,
                craftsPerSecondPerMachine
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
