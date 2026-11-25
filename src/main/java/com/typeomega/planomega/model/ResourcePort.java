package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable input or output slot on a recipe or virtual recipe node.
 */
public record ResourcePort(
        String id,
        PortDirection direction,
        ResourceKey resource,
        double amount,
        double chance,
        double tierChanceBoost,
        boolean catalyst,
        @Nullable String label
) {

    public ResourcePort {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(resource, "resource");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Port id must not be blank");
        }
        if (amount < 0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("Port amount must be finite and >= 0: " + amount);
        }
        if (chance < 0.0 || chance > 1.0 || !Double.isFinite(chance)) {
            throw new IllegalArgumentException("Port chance must be within [0.0, 1.0]: " + chance);
        }
        if (!Double.isFinite(tierChanceBoost)) {
            throw new IllegalArgumentException("tierChanceBoost must be finite");
        }
        if (label != null && label.isBlank()) {
            label = null;
        }
    }

    public static ResourcePort input(String id, ResourceKey resource, double amount) {
        return new ResourcePort(id, PortDirection.INPUT, resource, amount, 1.0, 0.0, false, null);
    }

    public static ResourcePort catalyst(String id, ResourceKey resource, double amount) {
        return new ResourcePort(id, PortDirection.INPUT, resource, amount, 1.0, 0.0, true, null);
    }

    public static ResourcePort output(String id, ResourceKey resource, double amount) {
        return new ResourcePort(id, PortDirection.OUTPUT, resource, amount, 1.0, 0.0, false, null);
    }

    public static ResourcePort chancedOutput(String id, ResourceKey resource, double amount, double chance, double tierBoost) {
        return new ResourcePort(id, PortDirection.OUTPUT, resource, amount, chance, tierBoost, false, null);
    }

    public String displayLabel() {
        return label != null ? label : resource.shortName();
    }
}
