package com.typeomega.planomega.recipe;

import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory searchable index of extracted {@link RecipeEntry} instances,
 * supporting producer/consumer lookups by {@link ResourceKey}, {@code @mod}/{@code @machine}/{@code #tag}
 * filters, and relevance-ranked search.
 */
public final class RecipeCatalog {

    public static final RecipeCatalog EMPTY = new RecipeCatalog(List.of());

    private final List<RecipeEntry> allRecipes;
    private final Map<String, RecipeEntry> byId;
    private final Map<String, List<RecipeEntry>> producersByResource;
    private final Map<String, List<RecipeEntry>> consumersByResource;

    public RecipeCatalog(List<RecipeEntry> recipes) {
        this.allRecipes = List.copyOf(recipes);
        Map<String, RecipeEntry> idMap = new LinkedHashMap<>();
        Map<String, List<RecipeEntry>> prodMap = new LinkedHashMap<>();
        Map<String, List<RecipeEntry>> consMap = new LinkedHashMap<>();

        for (RecipeEntry entry : this.allRecipes) {
            idMap.putIfAbsent(entry.recipeId(), entry);
            for (ResourcePort out : entry.outputs()) {
                prodMap.computeIfAbsent(out.resource().canonicalId(), k -> new ArrayList<>()).add(entry);
            }
            for (ResourcePort in : entry.inputs()) {
                consMap.computeIfAbsent(in.resource().canonicalId(), k -> new ArrayList<>()).add(entry);
            }
        }
        this.byId = Collections.unmodifiableMap(idMap);
        this.producersByResource = prodMap;
        this.consumersByResource = consMap;
    }

    public int size() {
        return allRecipes.size();
    }

    public Optional<RecipeEntry> findById(String recipeId) {
        return Optional.ofNullable(byId.get(recipeId));
    }

    public List<RecipeEntry> producersOf(ResourceKey key) {
        if (key == null) {
            return List.of();
        }
        return producersByResource.getOrDefault(key.canonicalId(), List.of());
    }

    public List<RecipeEntry> consumersOf(ResourceKey key) {
        if (key == null) {
            return List.of();
        }
        return consumersByResource.getOrDefault(key.canonicalId(), List.of());
    }

    /**
     * Finds alternative recipes that produce the given {@link ResourceKey} (or {@code #tag} if
     * {@code targetOutput.kind() == ResourceKind.TAG}), optionally excluding {@code excludeRecipeId}.
     */
    public List<RecipeEntry> findAlternatives(
            @Nullable ResourceKey targetOutput,
            @Nullable String excludeRecipeId
    ) {
        if (targetOutput == null) {
            return List.of();
        }
        if (targetOutput.kind() == ResourceKind.TAG) {
            return findAlternativesForTag(targetOutput.id(), excludeRecipeId);
        }
        List<RecipeEntry> producers = producersOf(targetOutput);
        if (producers.isEmpty()) {
            return List.of();
        }
        if (excludeRecipeId == null || excludeRecipeId.isBlank()) {
            return producers;
        }
        List<RecipeEntry> filtered = new ArrayList<>(producers.size());
        for (RecipeEntry entry : producers) {
            if (!entry.recipeId().equals(excludeRecipeId)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Finds alternative recipes that produce an output matching the given {@code #tag}
     * (with or without leading {@code #}), optionally excluding {@code excludeRecipeId}.
     */
    public List<RecipeEntry> findAlternativesForTag(
            @Nullable String tagFilter,
            @Nullable String excludeRecipeId
    ) {
        if (tagFilter == null || tagFilter.isBlank()) {
            return List.of();
        }
        String normalized = tagFilter.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<RecipeEntry> exactMatches = new ArrayList<>();
        List<RecipeEntry> partialMatches = new ArrayList<>();
        for (RecipeEntry entry : allRecipes) {
            if (excludeRecipeId != null && entry.recipeId().equals(excludeRecipeId)) {
                continue;
            }
            boolean exact = false;
            boolean partial = false;
            for (ResourcePort out : entry.outputs()) {
                if (out.resource().kind() == ResourceKind.TAG) {
                    String outTag = out.resource().id().toLowerCase(Locale.ROOT);
                    if (outTag.equals(normalized)) {
                        exact = true;
                        break;
                    } else if (outTag.contains(normalized)) {
                        partial = true;
                    }
                }
            }
            if (exact) {
                exactMatches.add(entry);
            } else if (partial) {
                partialMatches.add(entry);
            }
        }
        if (partialMatches.isEmpty()) {
            return exactMatches;
        }
        exactMatches.addAll(partialMatches);
        return exactMatches;
    }

    /**
     * Searches recipes matching the query string, optionally restricted to producers of {@code producedResource}
     * or consumers of {@code consumedResource}, ranked by relevance (exact title/output matches first).
     */
    public List<RecipeEntry> search(
            String query,
            @Nullable ResourceKey producedResource,
            @Nullable ResourceKey consumedResource,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        List<RecipeEntry> pool;
        if (producedResource != null) {
            pool = producersOf(producedResource);
        } else if (consumedResource != null) {
            pool = consumersOf(consumedResource);
        } else {
            pool = allRecipes;
        }

        if (pool.isEmpty()) {
            return List.of();
        }

        String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return pool.size() <= limit ? pool : pool.subList(0, limit);
        }

        String[] terms = trimmed.split("\\s+");
        String textQuery = buildTextQuery(terms);
        List<ScoredEntry> scored = new ArrayList<>();
        for (RecipeEntry entry : pool) {
            if (matchesAll(entry, terms)) {
                scored.add(new ScoredEntry(entry, scoreRelevance(entry, textQuery, terms)));
            }
        }

        if (scored.isEmpty()) {
            return List.of();
        }

        scored.sort(Comparator
                .comparingInt((ScoredEntry s) -> s.score).reversed()
                .thenComparingInt(s -> s.entry.title().length())
                .thenComparing(s -> s.entry.title(), String.CASE_INSENSITIVE_ORDER));

        int count = Math.min(limit, scored.size());
        List<RecipeEntry> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            results.add(scored.get(i).entry);
        }
        return results;
    }

    private static String buildTextQuery(String[] terms) {
        StringBuilder sb = new StringBuilder();
        for (String term : terms) {
            if (!term.startsWith("@") && !term.startsWith("#")) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(term);
            }
        }
        return sb.toString();
    }

    private static int scoreRelevance(RecipeEntry entry, String textQuery, String[] terms) {
        if (textQuery.isEmpty()) {
            return 10;
        }
        int score = 10;
        String titleLower = entry.title().toLowerCase(Locale.ROOT);
        // Strip trailing " (Machine)" suffix for exact item/recipe title matching
        int parenIdx = titleLower.lastIndexOf(" (");
        String bareTitle = parenIdx > 0 ? titleLower.substring(0, parenIdx) : titleLower;

        if (titleLower.equals(textQuery) || bareTitle.equals(textQuery)) {
            score = Math.max(score, 1200);
        } else if (bareTitle.startsWith(textQuery) || titleLower.startsWith(textQuery)) {
            score = Math.max(score, 600);
        } else if (titleLower.contains(textQuery)) {
            score = Math.max(score, 300);
        }

        for (ResourcePort out : entry.outputs()) {
            String outLabel = out.displayLabel().toLowerCase(Locale.ROOT);
            String outShort = out.resource().shortName().toLowerCase(Locale.ROOT);
            String outId = out.resource().id().toLowerCase(Locale.ROOT);
            if (outLabel.equals(textQuery) || outShort.equals(textQuery) || outId.equals(textQuery)) {
                score = Math.max(score, 1100);
                break;
            } else if (outLabel.startsWith(textQuery) || outShort.startsWith(textQuery)) {
                score = Math.max(score, 550);
            } else if (outLabel.contains(textQuery) || outShort.contains(textQuery)) {
                score = Math.max(score, 280);
            }
        }

        if (score < 200) {
            boolean allInTitleOrOut = true;
            for (String term : terms) {
                if (term.startsWith("@") || term.startsWith("#")) {
                    continue;
                }
                boolean hit = titleLower.contains(term);
                if (!hit) {
                    for (ResourcePort out : entry.outputs()) {
                        if (out.displayLabel().toLowerCase(Locale.ROOT).contains(term)
                                || out.resource().id().toLowerCase(Locale.ROOT).contains(term)) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit) {
                    allInTitleOrOut = false;
                    break;
                }
            }
            if (allInTitleOrOut) {
                score = Math.max(score, 150);
            }
        }

        return score;
    }

    private static boolean matchesAll(RecipeEntry entry, String[] terms) {
        for (String term : terms) {
            if (term.startsWith("@")) {
                if (term.length() == 1) {
                    continue;
                }
                String filter = term.substring(1);
                boolean modMatch = entry.sourceMod().toLowerCase(Locale.ROOT).contains(filter)
                        || entry.recipeTypeId().toLowerCase(Locale.ROOT).contains(filter)
                        || entry.machineLabel().toLowerCase(Locale.ROOT).contains(filter);
                if (!modMatch) {
                    return false;
                }
            } else if (term.startsWith("#")) {
                if (term.length() == 1) {
                    boolean hasAnyTag = entry.inputs().stream().anyMatch(p -> p.resource().kind() == ResourceKind.TAG)
                            || entry.outputs().stream().anyMatch(p -> p.resource().kind() == ResourceKind.TAG);
                    if (!hasAnyTag) {
                        return false;
                    }
                    continue;
                }
                String tag = term.substring(1);
                boolean tagHit = entry.inputs().stream().anyMatch(p ->
                                p.resource().kind() == ResourceKind.TAG
                                        && p.resource().id().toLowerCase(Locale.ROOT).contains(tag))
                        || entry.outputs().stream().anyMatch(p ->
                                p.resource().kind() == ResourceKind.TAG
                                        && p.resource().id().toLowerCase(Locale.ROOT).contains(tag));
                if (!tagHit && !entry.searchBlob().contains(tag)) {
                    return false;
                }
            } else if (!entry.searchBlob().contains(term)) {
                return false;
            }
        }
        return true;
    }

    private record ScoredEntry(RecipeEntry entry, int score) {
    }
}
