package com.typeomega.planomega.integration.emi;

import com.typeomega.planomega.integration.IntegrationBridge.XeiLookup;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.recipe.RecipeEntry;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Isolated EMI API lookup helper. Only classloaded when {@code emi} is present.
 */
public final class EmiLookupHelper {

    private EmiLookupHelper() {
    }

    public static Optional<XeiLookup> getHovered() {
        return getHovered(-1, -1);
    }

    public static Optional<XeiLookup> getHovered(double mouseX, double mouseY) {
        if (EmiApi.isSearchFocused()) {
            return Optional.empty();
        }
        EmiStackInteraction interaction = (mouseX >= 0 && mouseY >= 0)
                ? EmiApi.getHoveredStack((int) mouseX, (int) mouseY, false)
                : EmiApi.getHoveredStack(false);
        if (interaction == null || interaction.isEmpty()) {
            interaction = EmiApi.getHoveredStack(false);
        }
        if (interaction == null || interaction.isEmpty()) {
            return Optional.empty();
        }
        ResourceKey key = toResourceKey(interaction.getStack());
        EmiRecipe emiRecipe = interaction.getRecipeContext();
        if (emiRecipe != null) {
            ResourceLocation id = emiRecipe.getId();
            String recipeId = id != null ? id.toString() : null;
            return Optional.of(new XeiLookup(recipeId, key, convertEmiRecipe(emiRecipe)));
        }
        return key != null ? Optional.of(new XeiLookup(null, key, null)) : Optional.empty();
    }

    private static @Nullable ResourceKey toResourceKey(@Nullable EmiIngredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return null;
        }
        List<EmiStack> stacks = ingredient.getEmiStacks();
        if (stacks.isEmpty()) {
            return null;
        }
        EmiStack first = stacks.get(0);
        if (first.getKeyOfType(Fluid.class) != null) {
            return ResourceKey.fluid(first.getId().toString());
        }
        if (first.getKeyOfType(Item.class) != null) {
            return ResourceKey.item(first.getId().toString());
        }
        return null;
    }

    private static @Nullable RecipeEntry convertEmiRecipe(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        if (id == null) {
            return null;
        }
        List<ResourcePort> inputs = new ArrayList<>();
        int inIdx = 0;
        for (EmiIngredient ing : recipe.getInputs()) {
            ResourceKey key = toResourceKey(ing);
            if (key != null) {
                long amt = Math.max(1L, ing.getAmount());
                boolean catalyst = ing.getChance() == 0f;
                inputs.add(new ResourcePort(
                        "in_" + (inIdx++),
                        PortDirection.INPUT,
                        key,
                        amt,
                        1.0,
                        0.0,
                        catalyst,
                        ing.getEmiStacks().get(0).getName().getString()
                ));
            }
        }
        List<ResourcePort> outputs = new ArrayList<>();
        int outIdx = 0;
        for (EmiStack stack : recipe.getOutputs()) {
            ResourceKey key = toResourceKey(stack);
            if (key != null) {
                long amt = Math.max(1L, stack.getAmount());
                outputs.add(new ResourcePort(
                        "out_" + (outIdx++),
                        PortDirection.OUTPUT,
                        key,
                        amt,
                        stack.getChance() > 0f ? stack.getChance() : 1.0,
                        0.0,
                        false,
                        stack.getName().getString()
                ));
            }
        }
        if (inputs.isEmpty() && outputs.isEmpty()) {
            return null;
        }
        String catId = recipe.getCategory() != null && recipe.getCategory().getId() != null
                ? recipe.getCategory().getId().toString()
                : "emi:recipe";
        String title = !outputs.isEmpty() ? outputs.get(0).displayLabel() : id.getPath();
        return new RecipeEntry(
                id.toString(),
                catId,
                id.getNamespace(),
                null,
                title,
                catId,
                inputs,
                outputs,
                0L,
                20,
                0,
                null,
                0,
                false,
                false,
                1,
                null
        );
    }
}
