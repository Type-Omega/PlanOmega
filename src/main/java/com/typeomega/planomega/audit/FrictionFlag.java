package com.typeomega.planomega.audit;

import com.typeomega.planomega.model.ResourceKey;
import org.jetbrains.annotations.Nullable;

/**
 * Diagnostic flag identifying optimization opportunities, bottlenecks, or chemistry/progression
 * friction points in a production chain.
 */
public record FrictionFlag(
        Category category,
        Severity severity,
        @Nullable String nodeId,
        @Nullable ResourceKey resource,
        String title,
        String detail,
        String aiHint
) {

    public enum Severity {
        INFO(0xFF38BDF8),
        WARNING(0xFFF59E0B),
        CRITICAL(0xFFEF4444);

        private final int color;

        Severity(int color) {
            this.color = color;
        }

        public int color() {
            return color;
        }
    }

    public enum Category {
        ORPHAN_BYPRODUCT,
        UNCONNECTED_BYPRODUCT,
        BOTTLENECK_ENERGY,
        BOTTLENECK_MACHINES,
        BOTTLENECK_TIME,
        BOTTLENECK_HATCH,
        ELECTRICAL_SPIKE,
        REDUNDANT_PASSTHROUGH,
        ARTIFICIAL_JUMP,
        RECYCLE_IMBALANCE
    }
}
