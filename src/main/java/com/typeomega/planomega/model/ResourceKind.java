package com.typeomega.planomega.model;

/**
 * Classification of resources flowing through a {@link PlanGraph}.
 */
public enum ResourceKind {
    ITEM(0xFFE5A93C, "x"),
    FLUID(0xFF38BDF8, "mB"),
    ENERGY(0xFF4ADE80, "EU/t"),
    TAG(0xFFC084FC, "#");

    private final int color;
    private final String unitLabel;

    ResourceKind(int color, String unitLabel) {
        this.color = color;
        this.unitLabel = unitLabel;
    }

    /**
     * Default ARGB accent color for ports and edges of this resource kind.
     */
    public int color() {
        return color;
    }

    /**
     * Short display unit suffix used in canvas port labels.
     */
    public String unitLabel() {
        return unitLabel;
    }
}
