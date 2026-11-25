package com.typeomega.planomega.codec;

import com.typeomega.planomega.model.PlanGraph;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/**
 * Compact clipboard blueprint serializer/deserializer for {@link PlanGraph}
 * using UTF-8 JSON compressed with Gzip and encoded as URL-safe Base64
 * with the {@value #PREFIX} header.
 */
public final class BlueprintCodec {

    public static final String PREFIX = PlanCodec.SHARE_PREFIX;

    private BlueprintCodec() {
    }

    /**
     * Serializes an entire {@link PlanGraph} into a compact Gzip + Base64 blueprint string
     * prefixed with {@value #PREFIX}.
     */
    public static String encode(PlanGraph graph) throws IOException {
        Objects.requireNonNull(graph, "graph");
        return PlanCodec.encodeShareString(graph);
    }

    /**
     * Deserializes a compact blueprint string back into a {@link PlanGraph}.
     */
    public static PlanGraph decode(String blueprint) throws IOException {
        return PlanCodec.decodeShareString(blueprint);
    }

    /**
     * Returns {@code true} if the given clipboard text starts with {@value #PREFIX}.
     */
    public static boolean isBlueprint(@Nullable String raw) {
        return raw != null && raw.trim().startsWith(PREFIX);
    }
}
