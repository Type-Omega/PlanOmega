package com.typeomega.planomega.model;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical identifier for any item, fluid, tag, or energy stream in a production plan.
 */
public record ResourceKey(
        ResourceKind kind,
        String id,
        @Nullable String nbtTag
) {

    public static final String ENERGY_ID = "planomega:eu";

    public ResourceKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("ResourceKey id must not be blank");
        }
        if (nbtTag != null && nbtTag.isBlank()) {
            nbtTag = null;
        }
    }

    public static ResourceKey item(String id) {
        return new ResourceKey(ResourceKind.ITEM, id, null);
    }

    public static ResourceKey item(String id, @Nullable String nbtTag) {
        return new ResourceKey(ResourceKind.ITEM, id, nbtTag);
    }

    public static ResourceKey fluid(String id) {
        return new ResourceKey(ResourceKind.FLUID, id, null);
    }

    public static ResourceKey tag(String tagId) {
        return new ResourceKey(ResourceKind.TAG, tagId, null);
    }

    public static ResourceKey energy() {
        return new ResourceKey(ResourceKind.ENERGY, ENERGY_ID, null);
    }

    /**
     * Returns a stable string representation suitable for map keys, LP variables, and AI exports.
     */
    public String canonicalId() {
        String base = kind.name().toLowerCase(Locale.ROOT) + ":" + id;
        return nbtTag == null ? base : base + nbtTag;
    }

    /**
     * Returns a compact human-readable short name (stripping namespace if present).
     */
    public String shortName() {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (kind == ResourceKind.TAG) {
            return "#" + path;
        }
        return path;
    }

    /**
     * Checks whether two resource keys can connect along an edge.
     */
    public boolean isCompatibleWith(ResourceKey other) {
        if (other == null) {
            return false;
        }
        if (this.equals(other)) {
            return true;
        }
        // A specific item can feed a tag port of the same kind, or vice versa in planning mode.
        return (this.kind == ResourceKind.ITEM && other.kind == ResourceKind.TAG)
                || (this.kind == ResourceKind.TAG && other.kind == ResourceKind.ITEM);
    }
}
