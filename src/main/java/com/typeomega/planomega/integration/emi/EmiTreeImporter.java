package com.typeomega.planomega.integration.emi;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.integration.PendingRecipeQueue;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.recipe.RecipeEntry;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts an EMI crafting tree (either the active EMI BoM tree or a recursive expansion
 * from a root {@link EmiRecipe}) into a connected {@link PlanGraph} subgraph.
 */
public final class EmiTreeImporter {

    private static final double COL_SPACING = 260.0;
    private static final double ROW_SPACING = 140.0;
    private static final int DEFAULT_MAX_DEPTH = 12;

    private EmiTreeImporter() {
    }

    /**
     * Returns {@code true} if EMI currently has an active recipe tree with a non-null goal.
     */
    public static boolean hasActiveTree() {
        return getActiveGoalNode().isPresent();
    }

    /**
     * Imports EMI's active BoM recipe tree into a connected {@link PlanGraph} subgraph.
     */
    public static Optional<PlanGraph> importActiveTree() {
        Optional<Object> goalOpt = getActiveGoalNode();
        if (goalOpt.isEmpty()) {
            return Optional.empty();
        }
        BuildContext ctx = new BuildContext();
        RecipeNode root = buildFromMaterialNode(goalOpt.get(), 0, ctx, new HashSet<>());
        if (root == null && ctx.graph.nodes().isEmpty()) {
            return Optional.empty();
        }
        String name = root != null ? "EMI: " + root.title() : "EMI Recipe Tree";
        return Optional.of(ctx.graph.withName(name));
    }

    /**
     * Imports EMI's active BoM recipe tree and enqueues it into {@link PendingRecipeQueue}.
     */
    public static boolean enqueueActiveTree() {
        Optional<PlanGraph> imported = importActiveTree();
        if (imported.isPresent() && !imported.get().nodes().isEmpty()) {
            PendingRecipeQueue.enqueueSubgraph(imported.get());
            return true;
        }
        return false;
    }

    /**
     * Builds a connected {@link PlanGraph} subgraph starting from a root {@link EmiRecipe},
     * resolving upstream producers via {@link EmiApi#getRecipeManager()}.
     */
    public static PlanGraph fromRootRecipe(EmiRecipe rootRecipe) {
        return fromRootRecipe(rootRecipe, DEFAULT_MAX_DEPTH);
    }

    /**
     * Builds a connected {@link PlanGraph} subgraph starting from a root {@link EmiRecipe}
     * up to {@code maxDepth} upstream levels.
     */
    public static PlanGraph fromRootRecipe(EmiRecipe rootRecipe, int maxDepth) {
        BuildContext ctx = new BuildContext();
        EmiRecipeManager manager = EmiApi.getRecipeManager();
        RecipeNode root = buildFromEmiRecipe(rootRecipe, 0, Math.max(1, maxDepth), manager, ctx, new HashSet<>());
        String name = root != null ? "EMI: " + root.title() : "EMI Recipe Tree";
        return ctx.graph.withName(name);
    }

    private static Optional<Object> getActiveGoalNode() {
        try {
            Class<?> bomClass = Class.forName("dev.emi.emi.bom.BoM");
            Field treeField = bomClass.getField("tree");
            Object tree = treeField.get(null);
            if (tree == null) {
                return Optional.empty();
            }
            Field goalField = tree.getClass().getField("goal");
            Object goal = goalField.get(tree);
            return Optional.ofNullable(goal);
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("Could not inspect EMI BoM.tree", t);
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable RecipeNode buildFromMaterialNode(
            Object materialNode,
            int depth,
            BuildContext ctx,
            Set<String> activePath
    ) {
        try {
            Field recipeField = materialNode.getClass().getField("recipe");
            Object rawRecipe = recipeField.get(materialNode);
            if (!(rawRecipe instanceof EmiRecipe emiRecipe)) {
                return null;
            }
            String recipeKey = emiRecipe.getId() != null ? emiRecipe.getId().toString() : Integer.toHexString(System.identityHashCode(emiRecipe));
            if (!activePath.add(recipeKey)) {
                return ctx.nodesByRecipeKey.get(recipeKey);
            }
            RecipeNode parentNode = getOrCreateNode(emiRecipe, recipeKey, depth, ctx);
            if (parentNode == null) {
                activePath.remove(recipeKey);
                return null;
            }

            Field childrenField = materialNode.getClass().getField("children");
            Object rawChildren = childrenField.get(materialNode);
            if (rawChildren instanceof List<?> children) {
                for (Object child : children) {
                    if (child == null) {
                        continue;
                    }
                    RecipeNode childNode = buildFromMaterialNode(child, depth + 1, ctx, activePath);
                    if (childNode != null && !childNode.id().equals(parentNode.id())) {
                        connectCompatiblePorts(childNode, parentNode, ctx);
                    }
                }
            }
            activePath.remove(recipeKey);
            return parentNode;
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("Failed reading EMI MaterialNode", t);
            return null;
        }
    }

    private static @Nullable RecipeNode buildFromEmiRecipe(
            EmiRecipe emiRecipe,
            int depth,
            int maxDepth,
            @Nullable EmiRecipeManager manager,
            BuildContext ctx,
            Set<String> activePath
    ) {
        String recipeKey = emiRecipe.getId() != null ? emiRecipe.getId().toString() : Integer.toHexString(System.identityHashCode(emiRecipe));
        if (!activePath.add(recipeKey)) {
            return ctx.nodesByRecipeKey.get(recipeKey);
        }
        RecipeNode parentNode = getOrCreateNode(emiRecipe, recipeKey, depth, ctx);
        if (parentNode == null) {
            activePath.remove(recipeKey);
            return null;
        }
        if (depth < maxDepth && manager != null) {
            for (EmiIngredient ing : emiRecipe.getInputs()) {
                if (ing == null || ing.isEmpty() || ing.getChance() == 0f) {
                    continue;
                }
                List<EmiStack> stacks = ing.getEmiStacks();
                if (stacks.isEmpty()) {
                    continue;
                }
                List<EmiRecipe> producers = manager.getRecipesByOutput(stacks.get(0));
                if (producers != null && !producers.isEmpty()) {
                    EmiRecipe producer = producers.get(0);
                    RecipeNode childNode = buildFromEmiRecipe(producer, depth + 1, maxDepth, manager, ctx, activePath);
                    if (childNode != null && !childNode.id().equals(parentNode.id())) {
                        connectCompatiblePorts(childNode, parentNode, ctx);
                    }
                }
            }
        }
        activePath.remove(recipeKey);
        return parentNode;
    }

    private static @Nullable RecipeNode getOrCreateNode(
            EmiRecipe emiRecipe,
            String recipeKey,
            int depth,
            BuildContext ctx
    ) {
        RecipeNode existing = ctx.nodesByRecipeKey.get(recipeKey);
        if (existing != null) {
            return existing;
        }
        Optional<RecipeEntry> entryOpt = EmiLookupHelper.resolveOrConvert(emiRecipe);
        if (entryOpt.isEmpty()) {
            return null;
        }
        int row = ctx.rowsPerDepth.merge(depth, 1, Integer::sum) - 1;
        double x = -depth * COL_SPACING;
        double y = row * ROW_SPACING;
        RecipeNode node = entryOpt.get().toRecipeNode(x, y);
        ctx.nodesByRecipeKey.put(recipeKey, node);
        ctx.graph = ctx.graph.withNode(node);
        return node;
    }

    private static void connectCompatiblePorts(RecipeNode producer, RecipeNode consumer, BuildContext ctx) {
        for (ResourcePort outPort : producer.outputs()) {
            ResourceKey outKey = outPort.resource();
            // Prefer exact ResourceKey match first, then tag-compatible match.
            ResourcePort bestIn = null;
            for (ResourcePort inPort : consumer.inputs()) {
                if (outKey.equals(inPort.resource())) {
                    bestIn = inPort;
                    break;
                }
                if (bestIn == null && outKey.isCompatibleWith(inPort.resource())) {
                    bestIn = inPort;
                }
            }
            if (bestIn != null) {
                PlanEdge edge = new PlanEdge(
                        PlanGraph.newId("edge"),
                        producer.id(),
                        outPort.id(),
                        consumer.id(),
                        bestIn.id(),
                        outKey,
                        false
                );
                ctx.graph = ctx.graph.withEdge(edge);
            }
        }
    }

    private static final class BuildContext {
        PlanGraph graph = PlanGraph.empty("EMI Recipe Tree");
        final Map<String, RecipeNode> nodesByRecipeKey = new LinkedHashMap<>();
        final Map<Integer, Integer> rowsPerDepth = new HashMap<>();
    }
}
