package com.typeomega.planomega.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts standard Minecraft, KubeJS, and custom {@link RecipeManager} recipes
 * (crafting, cooking, stonecutting, smithing) into {@link RecipeEntry} descriptors.
 */
public final class VanillaRecipeExtractor {

    private VanillaRecipeExtractor() {
    }

    public static List<RecipeEntry> extract(RecipeManager recipeManager, RegistryAccess registryAccess, boolean skipGtceuRecipes) {
        List<RecipeEntry> entries = new ArrayList<>();
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            if (skipGtceuRecipes && GTCeuRecipeExtractor.isGtRecipe(recipe)) {
                continue;
            }
            if (recipe.isSpecial()) {
                continue;
            }
            RecipeEntry entry = convert(recipe, registryAccess);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static RecipeEntry convert(Recipe<?> recipe, RegistryAccess registryAccess) {
        ItemStack result = recipe.getResultItem(registryAccess);
        if (result.isEmpty()) {
            return null;
        }
        ResourceLocation outId = ForgeRegistries.ITEMS.getKey(result.getItem());
        if (outId == null) {
            return null;
        }

        List<ResourcePort> inputs = compressIngredients(recipe.getIngredients());
        if (inputs.isEmpty()) {
            return null;
        }

        ResourcePort outPort = new ResourcePort(
                "out_0",
                PortDirection.OUTPUT,
                ResourceKey.item(outId.toString()),
                result.getCount(),
                1.0,
                0.0,
                false,
                result.getHoverName().getString()
        );

        int duration = 20;
        if (recipe instanceof AbstractCookingRecipe cooking) {
            duration = Math.max(1, cooking.getCookingTime());
        } else if (recipe instanceof StonecutterRecipe || recipe instanceof SmithingTransformRecipe) {
            duration = 20;
        }

        ResourceLocation typeId = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        String typeStr = typeId != null ? typeId.toString() : "minecraft:crafting";
        String machineLabel = prettyTypeName(typeStr);

        return new RecipeEntry(
                recipe.getId().toString(),
                typeStr,
                recipe.getId().getNamespace(),
                null,
                result.getHoverName().getString(),
                machineLabel,
                inputs,
                List.of(outPort),
                0L,
                duration,
                0,
                null,
                0,
                false,
                false,
                1,
                null
        );
    }

    private static List<ResourcePort> compressIngredients(List<Ingredient> ingredients) {
        Map<String, CompressedItem> grouped = new LinkedHashMap<>();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) {
                continue;
            }
            String tagId = extractTagId(ing);
            if (tagId != null) {
                String keyStr = "tag:" + tagId;
                CompressedItem existing = grouped.get(keyStr);
                if (existing == null) {
                    ResourceKey tagKey = ResourceKey.tag(tagId);
                    grouped.put(keyStr, new CompressedItem(tagKey, tagKey.shortName(), 1));
                } else {
                    existing.count += 1;
                }
                continue;
            }
            ItemStack[] stacks = ing.getItems();
            if (stacks.length == 0) {
                continue;
            }
            ItemStack first = stacks[0];
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(first.getItem());
            if (itemId == null) {
                continue;
            }
            String keyStr = itemId.toString();
            CompressedItem existing = grouped.get(keyStr);
            if (existing == null) {
                grouped.put(keyStr, new CompressedItem(
                        ResourceKey.item(keyStr),
                        first.getHoverName().getString(),
                        Math.max(1, first.getCount())
                ));
            } else {
                existing.count += Math.max(1, first.getCount());
            }
        }

        List<ResourcePort> ports = new ArrayList<>(grouped.size());
        int idx = 0;
        for (CompressedItem item : grouped.values()) {
            ports.add(new ResourcePort(
                    "in_" + (idx++),
                    PortDirection.INPUT,
                    item.key,
                    item.count,
                    1.0,
                    0.0,
                    false,
                    item.label
            ));
        }
        return ports;
    }

    private static String extractTagId(Ingredient ing) {
        try {
            JsonElement json = ing.toJson();
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                if (obj.has("tag") && obj.get("tag").isJsonPrimitive()) {
                    return obj.get("tag").getAsString();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String prettyTypeName(String typeId) {
        int colon = typeId.indexOf(':');
        String path = colon >= 0 ? typeId.substring(colon + 1) : typeId;
        return switch (path) {
            case "crafting" -> "Crafting";
            case "smelting" -> "Furnace";
            case "blasting" -> "Blast Furnace";
            case "smoking" -> "Smoker";
            case "stonecutting" -> "Stonecutter";
            case "smithing" -> "Smithing";
            default -> path;
        };
    }

    private static final class CompressedItem {
        final ResourceKey key;
        final String label;
        int count;

        CompressedItem(ResourceKey key, String label, int count) {
            this.key = key;
            this.label = label;
            this.count = count;
        }
    }
}
