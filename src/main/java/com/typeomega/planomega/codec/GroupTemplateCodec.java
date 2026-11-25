package com.typeomega.planomega.codec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.VirtualRecipeNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JSON serializer and instantiator for reusable {@link GroupNode} / subgraph templates
 * stored under {@code config/planomega/templates/}, as well as ID-remapping subgraph cloning
 * for Ctrl+C / Ctrl+V copy-paste.
 */
public final class GroupTemplateCodec {

    public static final String TEMPLATE_EXT = ".template.json";
    public static final int TEMPLATE_VERSION = 1;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private GroupTemplateCodec() {
    }

    public record GroupTemplate(String name, String description, PlanGraph subgraph) {
        public GroupTemplate {
            name = (name == null || name.isBlank()) ? "Group Template" : name.trim();
            description = description == null ? "" : description;
            Objects.requireNonNull(subgraph, "subgraph");
        }
    }

    public static String toJson(GroupTemplate template) {
        Objects.requireNonNull(template, "template");
        JsonObject root = new JsonObject();
        root.addProperty("templateVersion", TEMPLATE_VERSION);
        root.addProperty("name", template.name());
        root.addProperty("description", template.description());
        root.add("graph", PlanCodec.serializeGraph(template.subgraph()));
        return PRETTY_GSON.toJson(root);
    }

    public static String toJson(String name, String description, PlanGraph subgraph) {
        return toJson(new GroupTemplate(name, description, subgraph));
    }

    public static GroupTemplate fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Template JSON must not be null or blank");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Corrupted template JSON: " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Template JSON root must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        String name = root.has("name") && root.get("name").isJsonPrimitive()
                ? root.get("name").getAsString()
                : "Group Template";
        String description = root.has("description") && root.get("description").isJsonPrimitive()
                ? root.get("description").getAsString()
                : "";
        if (!root.has("graph") || !root.get("graph").isJsonObject()) {
            throw new IllegalArgumentException("Template JSON missing 'graph' object");
        }
        PlanGraph subgraph = PlanCodec.fromJson(COMPACT_GSON.toJson(root.getAsJsonObject("graph")));
        return new GroupTemplate(name, description, subgraph);
    }

    /**
     * Clones {@code subgraph} with fresh node and edge IDs (preserving internal edges,
     * group membership, and all node properties) and translates all cloned nodes by {@code (offsetX, offsetY)}.
     */
    public static PlanGraph cloneWithFreshIds(PlanGraph subgraph, double offsetX, double offsetY) {
        if (subgraph == null || subgraph.nodes().isEmpty()) {
            return PlanGraph.empty("Empty");
        }

        Map<String, String> idMap = new HashMap<>();
        for (PlanNode node : subgraph.nodes().values()) {
            String prefix = prefixFor(node);
            idMap.put(node.id(), PlanGraph.newId(prefix));
        }

        JsonObject serialized = PlanCodec.serializeGraph(subgraph);
        if (serialized.has("nodes") && serialized.get("nodes").isJsonArray()) {
            JsonArray nodesArr = serialized.getAsJsonArray("nodes");
            for (JsonElement elem : nodesArr) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject obj = elem.getAsJsonObject();
                String oldId = obj.has("id") ? obj.get("id").getAsString() : null;
                if (oldId != null && idMap.containsKey(oldId)) {
                    obj.addProperty("id", idMap.get(oldId));
                }
                if (obj.has("groupId") && obj.get("groupId").isJsonPrimitive()) {
                    String oldGid = obj.get("groupId").getAsString();
                    if (idMap.containsKey(oldGid)) {
                        obj.addProperty("groupId", idMap.get(oldGid));
                    } else {
                        obj.remove("groupId");
                    }
                }
                if (obj.has("childNodeIds") && obj.get("childNodeIds").isJsonArray()) {
                    JsonArray oldChildren = obj.getAsJsonArray("childNodeIds");
                    JsonArray newChildren = new JsonArray();
                    for (JsonElement c : oldChildren) {
                        if (c.isJsonPrimitive()) {
                            String remapped = idMap.get(c.getAsString());
                            if (remapped != null) {
                                newChildren.add(remapped);
                            }
                        }
                    }
                    obj.add("childNodeIds", newChildren);
                }
            }
        }

        if (serialized.has("edges") && serialized.get("edges").isJsonArray()) {
            JsonArray edgesArr = serialized.getAsJsonArray("edges");
            for (JsonElement elem : edgesArr) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject obj = elem.getAsJsonObject();
                obj.addProperty("id", PlanGraph.newId("edge"));
                if (obj.has("fromNodeId") && obj.get("fromNodeId").isJsonPrimitive()) {
                    String remapped = idMap.get(obj.get("fromNodeId").getAsString());
                    if (remapped != null) {
                        obj.addProperty("fromNodeId", remapped);
                    }
                }
                if (obj.has("toNodeId") && obj.get("toNodeId").isJsonPrimitive()) {
                    String remapped = idMap.get(obj.get("toNodeId").getAsString());
                    if (remapped != null) {
                        obj.addProperty("toNodeId", remapped);
                    }
                }
            }
        }

        PlanGraph cloned = PlanCodec.fromJson(COMPACT_GSON.toJson(serialized));
        if (offsetX != 0.0 || offsetY != 0.0) {
            cloned = cloned.moveNodes(cloned.nodes().keySet(), offsetX, offsetY);
        }
        return cloned;
    }

    /**
     * Instantiates {@code template} with fresh IDs positioned so its top-left corner sits at {@code (targetX, targetY)}.
     */
    public static PlanGraph instantiate(GroupTemplate template, double targetX, double targetY) {
        Objects.requireNonNull(template, "template");
        PlanGraph sub = template.subgraph();
        if (sub.nodes().isEmpty()) {
            return sub;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        for (PlanNode node : sub.nodes().values()) {
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
        }
        double dx = Double.isFinite(minX) ? (targetX - minX) : 0.0;
        double dy = Double.isFinite(minY) ? (targetY - minY) : 0.0;
        return cloneWithFreshIds(sub, dx, dy);
    }

    private static String prefixFor(PlanNode node) {
        if (node instanceof GroupNode) {
            return "grp";
        }
        if (node instanceof VirtualRecipeNode) {
            return "sim";
        }
        if (node instanceof StickyNoteNode) {
            return "note";
        }
        if (node instanceof RecipeNode) {
            return "rec";
        }
        return "node";
    }
}
