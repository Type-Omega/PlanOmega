package com.typeomega.planomega.integration;

import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.recipe.RecipeCatalogManager;
import com.typeomega.planomega.recipe.RecipeEntry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe pending queue for recipes and subgraphs sent from external recipe viewers
 * (JEI / EMI) to be inserted onto the Plan Omega canvas.
 */
public final class PendingRecipeQueue {

    private static final Deque<RecipeEntry> PENDING_RECIPES = new ConcurrentLinkedDeque<>();
    private static final Deque<PlanGraph> PENDING_SUBGRAPHS = new ConcurrentLinkedDeque<>();

    private PendingRecipeQueue() {
    }

    public static void enqueue(@Nullable RecipeEntry recipe) {
        if (recipe != null) {
            PENDING_RECIPES.addLast(recipe);
        }
    }

    public static boolean enqueueById(@Nullable String recipeId) {
        if (recipeId == null || recipeId.isBlank()) {
            return false;
        }
        Optional<RecipeEntry> found = RecipeCatalogManager.get().findById(recipeId);
        if (found.isPresent()) {
            PENDING_RECIPES.addLast(found.get());
            return true;
        }
        return false;
    }

    public static boolean enqueueLookup(@Nullable IntegrationBridge.XeiLookup lookup) {
        if (lookup == null) {
            return false;
        }
        if (lookup.recipeId() != null) {
            Optional<RecipeEntry> catalogMatch = RecipeCatalogManager.get().findById(lookup.recipeId());
            if (catalogMatch.isPresent()) {
                PENDING_RECIPES.addLast(catalogMatch.get());
                return true;
            }
        }
        if (lookup.fallbackRecipe() != null) {
            PENDING_RECIPES.addLast(lookup.fallbackRecipe());
            return true;
        }
        return false;
    }

    public static Optional<RecipeEntry> poll() {
        return Optional.ofNullable(PENDING_RECIPES.pollFirst());
    }

    public static List<RecipeEntry> drainAll() {
        List<RecipeEntry> drained = new ArrayList<>();
        RecipeEntry next;
        while ((next = PENDING_RECIPES.pollFirst()) != null) {
            drained.add(next);
        }
        return drained;
    }

    public static boolean hasPending() {
        return !PENDING_RECIPES.isEmpty();
    }

    public static int size() {
        return PENDING_RECIPES.size();
    }

    public static void clear() {
        PENDING_RECIPES.clear();
    }

    public static void enqueueSubgraph(@Nullable PlanGraph subgraph) {
        if (subgraph != null && !subgraph.nodes().isEmpty()) {
            PENDING_SUBGRAPHS.addLast(subgraph);
        }
    }

    public static Optional<PlanGraph> pollSubgraph() {
        return Optional.ofNullable(PENDING_SUBGRAPHS.pollFirst());
    }

    public static List<PlanGraph> drainSubgraphs() {
        List<PlanGraph> drained = new ArrayList<>();
        PlanGraph next;
        while ((next = PENDING_SUBGRAPHS.pollFirst()) != null) {
            drained.add(next);
        }
        return drained;
    }

    public static boolean hasPendingSubgraphs() {
        return !PENDING_SUBGRAPHS.isEmpty();
    }

    public static int subgraphSize() {
        return PENDING_SUBGRAPHS.size();
    }

    public static void clearSubgraphs() {
        PENDING_SUBGRAPHS.clear();
    }

    public static void clearAll() {
        PENDING_RECIPES.clear();
        PENDING_SUBGRAPHS.clear();
    }
}
