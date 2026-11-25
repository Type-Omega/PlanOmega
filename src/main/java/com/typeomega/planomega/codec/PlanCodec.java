package com.typeomega.planomega.codec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.VirtualRecipeNode;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Deterministic, reflection-free JSON and clipboard share-string serializer for {@link PlanGraph}.
 */
public final class PlanCodec {

    public static final int CODEC_SCHEMA_VERSION = 2;
    public static final String SHARE_PREFIX = "PLANOMEGA1:";
    private static final int MAX_SHARE_BYTES = 8 * 1024 * 1024;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlanCodec() {
    }

    public static String toJson(PlanGraph graph) {
        return PRETTY_GSON.toJson(serializeGraph(graph));
    }

    public static String toCompactJson(PlanGraph graph) {
        return COMPACT_GSON.toJson(serializeGraph(graph));
    }

    public static PlanGraph fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Plan JSON must not be null or blank");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Corrupted plan JSON: " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Plan JSON root must be a JSON object");
        }
        return deserializeGraph(parsed.getAsJsonObject());
    }

    public static String encodeShareString(PlanGraph graph) throws IOException {
        byte[] utf8 = toCompactJson(graph).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(utf8);
        }
        return SHARE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    public static PlanGraph decodeShareString(String payload) throws IOException {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Share string is null or blank");
        }
        String trimmed = payload.trim();
        if (!trimmed.startsWith(SHARE_PREFIX)) {
            throw new IllegalArgumentException("Invalid Plan Omega share header");
        }
        String body = trimmed.substring(SHARE_PREFIX.length()).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Empty Plan Omega share payload");
        }
        if (body.length() > MAX_SHARE_BYTES * 2) {
            throw new IllegalArgumentException("Share string exceeds maximum allowed length");
        }
        final byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64 in Plan Omega share string", e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gunzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = gunzip.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SHARE_BYTES) {
                    throw new IOException("Decompressed plan exceeds safety limit of " + MAX_SHARE_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
        }
        return fromJson(out.toString(StandardCharsets.UTF_8));
    }

    public static JsonObject serializeGraph(PlanGraph graph) {
        JsonObject root = new JsonObject();
        int schemaVersion = Math.max(graph.schemaVersion(), hasV2Fields(graph) ? CODEC_SCHEMA_VERSION : 1);
        root.addProperty("schemaVersion", schemaVersion);
        root.addProperty("id", graph.id());
        root.addProperty("name", graph.name());
        root.addProperty("author", graph.author());
        root.addProperty("description", graph.description());

        JsonArray nodesArr = new JsonArray();
        for (PlanNode node : graph.nodes().values()) {
            nodesArr.add(serializeNode(node));
        }
        root.add("nodes", nodesArr);

        JsonArray edgesArr = new JsonArray();
        for (PlanEdge edge : graph.edges().values()) {
            edgesArr.add(serializeEdge(edge));
        }
        root.add("edges", edgesArr);
        return root;
    }

    private static JsonObject serializeNode(PlanNode node) {
        JsonObject obj = new JsonObject();
        if (node instanceof RecipeNode r) {
            obj.addProperty("type", "recipe");
            writeBaseNodeFields(obj, r);
            obj.addProperty("recipeId", r.recipeId());
            obj.addProperty("recipeTypeId", r.recipeTypeId());
            if (r.machineId() != null) {
                obj.addProperty("machineId", r.machineId());
            }
            obj.addProperty("title", r.title());
            obj.add("inputs", serializePorts(r.inputs()));
            obj.add("outputs", serializePorts(r.outputs()));
            obj.addProperty("eut", r.eut());
            obj.addProperty("durationTicks", r.durationTicks());
            obj.addProperty("tier", r.tier());
            if (r.circuit() != null) {
                obj.addProperty("circuit", r.circuit());
            }
            obj.addProperty("coilTemp", r.coilTemp());
            obj.addProperty("cleanroom", r.cleanroom());
            obj.addProperty("perfectOverclock", r.perfectOverclock());
            obj.addProperty("parallelLimit", r.parallelLimit());
            if (r.targetRate() != null) {
                obj.addProperty("targetRate", r.targetRate());
            }
            if (r.targetPortId() != null) {
                obj.addProperty("targetPortId", r.targetPortId());
            }
            if (r.lockedRate() != null) {
                obj.addProperty("lockedRate", r.lockedRate());
            }
            if (r.coilTier() != null) {
                obj.addProperty("coilTier", r.coilTier());
            }
            if (r.parallelCount() > 1) {
                obj.addProperty("parallelCount", r.parallelCount());
            }
        } else if (node instanceof VirtualRecipeNode v) {
            obj.addProperty("type", "virtual_recipe");
            writeBaseNodeFields(obj, v);
            if (v.baseRecipeId() != null) {
                obj.addProperty("baseRecipeId", v.baseRecipeId());
            }
            obj.addProperty("recipeTypeId", v.recipeTypeId());
            if (v.machineId() != null) {
                obj.addProperty("machineId", v.machineId());
            }
            obj.addProperty("title", v.title());
            obj.add("inputs", serializePorts(v.inputs()));
            obj.add("outputs", serializePorts(v.outputs()));
            obj.addProperty("eut", v.eut());
            obj.addProperty("durationTicks", v.durationTicks());
            obj.addProperty("tier", v.tier());
            if (v.circuit() != null) {
                obj.addProperty("circuit", v.circuit());
            }
            obj.addProperty("coilTemp", v.coilTemp());
            obj.addProperty("cleanroom", v.cleanroom());
            obj.addProperty("perfectOverclock", v.perfectOverclock());
            obj.addProperty("notes", v.notes());
            if (v.targetRate() != null) {
                obj.addProperty("targetRate", v.targetRate());
            }
            if (v.targetPortId() != null) {
                obj.addProperty("targetPortId", v.targetPortId());
            }
        } else if (node instanceof StickyNoteNode note) {
            obj.addProperty("type", "sticky_note");
            writeBaseNodeFields(obj, note);
            obj.addProperty("title", note.title());
            obj.addProperty("text", note.text());
            obj.addProperty("color", note.color());
        } else if (node instanceof GroupNode group) {
            obj.addProperty("type", "group");
            writeBaseNodeFields(obj, group);
            obj.addProperty("title", group.title());
            obj.addProperty("color", group.color());
            obj.addProperty("collapsed", group.collapsed());
            JsonArray children = new JsonArray();
            group.childNodeIds().forEach(children::add);
            obj.add("childNodeIds", children);
        }
        return obj;
    }

    private static void writeBaseNodeFields(JsonObject obj, PlanNode node) {
        obj.addProperty("id", node.id());
        obj.addProperty("x", node.x());
        obj.addProperty("y", node.y());
        obj.addProperty("width", node.width());
        obj.addProperty("height", node.height());
        if (node.groupId() != null) {
            obj.addProperty("groupId", node.groupId());
        }
    }

    private static JsonArray serializePorts(List<ResourcePort> ports) {
        JsonArray arr = new JsonArray();
        for (ResourcePort port : ports) {
            JsonObject p = new JsonObject();
            p.addProperty("id", port.id());
            p.addProperty("direction", port.direction().name());
            p.add("resource", serializeResourceKey(port.resource()));
            p.addProperty("amount", port.amount());
            p.addProperty("chance", port.chance());
            p.addProperty("tierChanceBoost", port.tierChanceBoost());
            p.addProperty("catalyst", port.catalyst());
            if (port.label() != null) {
                p.addProperty("label", port.label());
            }
            arr.add(p);
        }
        return arr;
    }

    private static JsonObject serializeResourceKey(ResourceKey key) {
        JsonObject obj = new JsonObject();
        obj.addProperty("kind", key.kind().name());
        obj.addProperty("id", key.id());
        if (key.nbtTag() != null) {
            obj.addProperty("nbtTag", key.nbtTag());
        }
        return obj;
    }

    private static JsonObject serializeEdge(PlanEdge edge) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", edge.id());
        obj.addProperty("fromNodeId", edge.fromNodeId());
        obj.addProperty("fromPortId", edge.fromPortId());
        obj.addProperty("toNodeId", edge.toNodeId());
        obj.addProperty("toPortId", edge.toPortId());
        obj.add("resource", serializeResourceKey(edge.resource()));
        obj.addProperty("manualLock", edge.manualLock());
        return obj;
    }

    private static PlanGraph deserializeGraph(JsonObject root) {
        int rawSchema = getInt(root, "schemaVersion", PlanGraph.CURRENT_SCHEMA_VERSION);
        int schema = migrateSchemaIfNeeded(root, rawSchema);
        String id = getNonBlankString(root, "id", PlanGraph.newId("plan"));
        String name = getNonBlankString(root, "name", getNonBlankString(root, "title", "Untitled Plan"));
        String author = getString(root, "author", "");
        String description = getString(root, "description", "");

        Map<String, PlanNode> nodes = new LinkedHashMap<>();
        JsonArray nodesArr = getJsonArray(root, "nodes");
        if (nodesArr != null) {
            for (JsonElement elem : nodesArr) {
                if (elem == null || !elem.isJsonObject()) {
                    continue;
                }
                PlanNode node = deserializeNode(elem.getAsJsonObject());
                nodes.put(node.id(), node);
            }
        }

        Map<String, PlanEdge> edges = new LinkedHashMap<>();
        JsonArray edgesArr = getJsonArray(root, "edges");
        if (edgesArr != null) {
            for (JsonElement elem : edgesArr) {
                if (elem == null || !elem.isJsonObject()) {
                    continue;
                }
                PlanEdge edge = deserializeEdge(elem.getAsJsonObject());
                if (edge != null && nodes.containsKey(edge.fromNodeId()) && nodes.containsKey(edge.toNodeId())) {
                    edges.put(edge.id(), edge);
                }
            }
        }

        return new PlanGraph(schema, id, name, author, description, nodes, edges);
    }

    private static boolean hasV2Fields(PlanGraph graph) {
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof RecipeNode r && (r.coilTier() != null || r.parallelCount() > 1)) {
                return true;
            }
        }
        return false;
    }

    private static int migrateSchemaIfNeeded(JsonObject root, int rawSchema) {
        // Legacy v0 (or missing schemaVersion) migrates cleanly to CODEC_SCHEMA_VERSION.
        if (rawSchema <= 0) {
            return CODEC_SCHEMA_VERSION;
        }
        // Future schema versions are loaded best-effort and normalized to CODEC_SCHEMA_VERSION.
        if (rawSchema > CODEC_SCHEMA_VERSION) {
            return CODEC_SCHEMA_VERSION;
        }
        return rawSchema;
    }

    private static PlanNode deserializeNode(JsonObject obj) {
        String type = getNonBlankString(obj, "type", "recipe");
        String id = getNonBlankString(obj, "id", PlanGraph.newId("node"));
        double x = getDouble(obj, "x", 0.0);
        double y = getDouble(obj, "y", 0.0);
        double width = getDouble(obj, "width", 0.0);
        double height = getDouble(obj, "height", 0.0);
        String groupId = getNullableString(obj, "groupId");

        return switch (type) {
            case "recipe" -> new RecipeNode(
                    id, x, y, width, height, groupId,
                    getNonBlankString(obj, "recipeId", "unknown:recipe"),
                    getNonBlankString(obj, "recipeTypeId", "gtceu:unknown"),
                    getNullableString(obj, "machineId"),
                    getNonBlankString(obj, "title", "Recipe"),
                    deserializePorts(getJsonArray(obj, "inputs"), PortDirection.INPUT),
                    deserializePorts(getJsonArray(obj, "outputs"), PortDirection.OUTPUT),
                    getLong(obj, "eut", 0L),
                    Math.max(1, getInt(obj, "durationTicks", 20)),
                    Math.max(0, getInt(obj, "tier", 1)),
                    getNullableInt(obj, "circuit"),
                    Math.max(0, getInt(obj, "coilTemp", 0)),
                    getBoolean(obj, "cleanroom", false),
                    getBoolean(obj, "perfectOverclock", false),
                    Math.max(1, getInt(obj, "parallelLimit", 1)),
                    getNullableDouble(obj, "targetRate"),
                    getNullableString(obj, "targetPortId"),
                    getNullableDouble(obj, "lockedRate"),
                    getNullableString(obj, "coilTier"),
                    Math.max(1, getInt(obj, "parallelCount", 1))
            );
            case "virtual_recipe" -> new VirtualRecipeNode(
                    id, x, y, width, height, groupId,
                    getNullableString(obj, "baseRecipeId"),
                    getNonBlankString(obj, "recipeTypeId", "planomega:virtual"),
                    getNullableString(obj, "machineId"),
                    getNonBlankString(obj, "title", "Virtual Step"),
                    deserializePorts(getJsonArray(obj, "inputs"), PortDirection.INPUT),
                    deserializePorts(getJsonArray(obj, "outputs"), PortDirection.OUTPUT),
                    getLong(obj, "eut", 0L),
                    Math.max(1, getInt(obj, "durationTicks", 20)),
                    Math.max(0, getInt(obj, "tier", 1)),
                    getNullableInt(obj, "circuit"),
                    Math.max(0, getInt(obj, "coilTemp", 0)),
                    getBoolean(obj, "cleanroom", false),
                    getBoolean(obj, "perfectOverclock", false),
                    getString(obj, "notes", ""),
                    getNullableDouble(obj, "targetRate"),
                    getNullableString(obj, "targetPortId")
            );
            case "sticky_note" -> new StickyNoteNode(
                    id, x, y, width, height, groupId,
                    getNonBlankString(obj, "title", "Note"),
                    getString(obj, "text", ""),
                    getInt(obj, "color", StickyNoteNode.DEFAULT_COLOR)
            );
            case "group" -> {
                List<String> children = new ArrayList<>();
                JsonArray childrenArr = getJsonArray(obj, "childNodeIds");
                if (childrenArr != null) {
                    for (JsonElement elem : childrenArr) {
                        if (elem != null && elem.isJsonPrimitive()) {
                            String childId = elem.getAsString();
                            if (!childId.isBlank()) {
                                children.add(childId);
                            }
                        }
                    }
                }
                yield new GroupNode(
                        id, x, y, width, height, groupId,
                        getNonBlankString(obj, "title", "Group"),
                        getInt(obj, "color", GroupNode.DEFAULT_COLOR),
                        getBoolean(obj, "collapsed", false),
                        children
                );
            }
            default -> throw new IllegalArgumentException("Unknown PlanNode type: " + type);
        };
    }

    private static List<ResourcePort> deserializePorts(@Nullable JsonArray arr, PortDirection fallbackDir) {
        if (arr == null) {
            return List.of();
        }
        List<ResourcePort> ports = new ArrayList<>(arr.size());
        for (JsonElement elem : arr) {
            if (elem == null || !elem.isJsonObject()) {
                continue;
            }
            JsonObject p = elem.getAsJsonObject();
            PortDirection dir = parseEnum(PortDirection.class, getString(p, "direction", fallbackDir.name()), fallbackDir);
            double amount = Math.max(0.0, getDouble(p, "amount", 1.0));
            double chance = Math.min(1.0, Math.max(0.0, getDouble(p, "chance", 1.0)));
            double tierBoost = getDouble(p, "tierChanceBoost", 0.0);
            ports.add(new ResourcePort(
                    getNonBlankString(p, "id", PlanGraph.newId("port")),
                    dir,
                    deserializeResourceKey(getJsonObject(p, "resource")),
                    amount,
                    chance,
                    tierBoost,
                    getBoolean(p, "catalyst", false),
                    getNullableString(p, "label")
            ));
        }
        return ports;
    }

    private static ResourceKey deserializeResourceKey(@Nullable JsonObject obj) {
        if (obj == null) {
            return ResourceKey.item("minecraft:air");
        }
        ResourceKind kind = parseEnum(ResourceKind.class, getString(obj, "kind", ResourceKind.ITEM.name()), ResourceKind.ITEM);
        String id = getNonBlankString(obj, "id", "minecraft:air");
        String nbt = getNullableString(obj, "nbtTag");
        return new ResourceKey(kind, id, nbt);
    }

    private static @Nullable PlanEdge deserializeEdge(JsonObject obj) {
        String fromNodeId = getString(obj, "fromNodeId", "");
        String fromPortId = getString(obj, "fromPortId", "");
        String toNodeId = getString(obj, "toNodeId", "");
        String toPortId = getString(obj, "toPortId", "");
        if (fromNodeId.isBlank() || fromPortId.isBlank() || toNodeId.isBlank() || toPortId.isBlank()) {
            return null;
        }
        return new PlanEdge(
                getNonBlankString(obj, "id", PlanGraph.newId("edge")),
                fromNodeId,
                fromPortId,
                toNodeId,
                toPortId,
                deserializeResourceKey(getJsonObject(obj, "resource")),
                getBoolean(obj, "manualLock", false)
        );
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, raw.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static @Nullable JsonArray getJsonArray(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static @Nullable JsonObject getJsonObject(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static String getNonBlankString(JsonObject obj, String key, String fallback) {
        String val = getString(obj, key, fallback);
        return val.isBlank() ? fallback : val;
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : fallback;
    }

    private static @Nullable String getNullableString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            String s = el.getAsString();
            return s.isBlank() ? null : s;
        }
        return null;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    private static @Nullable Integer getNullableInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static long getLong(JsonObject obj, String key, long fallback) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsLong();
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    private static double getDouble(JsonObject obj, String key, double fallback) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                double val = el.getAsDouble();
                if (Double.isFinite(val)) {
                    return val;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    private static @Nullable Double getNullableDouble(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                double val = el.getAsDouble();
                if (Double.isFinite(val)) {
                    return val;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsBoolean();
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }
}
