package com.typeomega.planomega.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VoltageTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Isolated GTCEu Modern (7.5.3) recipe extractor. Only classloaded when {@code gtceu} is present.
 * Captures GTCEu, GTNA, KubeJS GTCEu, and TypeOmega machine recipes with full EU/t, circuit,
 * coil temperature, cleanroom, catalyst, tag, fluid, and ChanceLogic-aware chanced output metadata.
 */
public final class GTCeuRecipeExtractor {

    private GTCeuRecipeExtractor() {
    }

    public static boolean isGtRecipe(Recipe<?> recipe) {
        return recipe instanceof GTRecipe;
    }

    public static List<RecipeEntry> extract(RecipeManager recipeManager) {
        List<RecipeEntry> entries = new ArrayList<>();
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            if (recipe instanceof GTRecipe gtRecipe) {
                RecipeEntry entry = convert(gtRecipe);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private static @Nullable RecipeEntry convert(GTRecipe recipe) {
        List<ResourcePort> inputs = new ArrayList<>();
        List<ResourcePort> outputs = new ArrayList<>();
        Integer circuit = null;

        List<Content> itemInContents = recipe.getInputContents(ItemRecipeCapability.CAP);
        ChanceLogic itemInLogic = getChanceLogic(recipe, ItemRecipeCapability.CAP, IO.IN);
        double[] itemInChances = computeEffectiveChances(itemInContents, itemInLogic);

        int inIdx = 0;
        for (int i = 0; i < itemInContents.size(); i++) {
            Content content = itemInContents.get(i);
            if (!(content.content instanceof Ingredient ing)) {
                continue;
            }
            Integer detectedCircuit = tryExtractCircuit(ing);
            if (detectedCircuit != null) {
                circuit = detectedCircuit;
                continue;
            }
            ResourcePort port = toItemPort("in_" + (inIdx++), PortDirection.INPUT, ing, content, itemInChances[i]);
            if (port != null) {
                inputs.add(port);
            }
        }

        List<Content> fluidInContents = recipe.getInputContents(FluidRecipeCapability.CAP);
        ChanceLogic fluidInLogic = getChanceLogic(recipe, FluidRecipeCapability.CAP, IO.IN);
        double[] fluidInChances = computeEffectiveChances(fluidInContents, fluidInLogic);

        for (int i = 0; i < fluidInContents.size(); i++) {
            Content content = fluidInContents.get(i);
            if (content.content instanceof FluidIngredient fluidIng) {
                ResourcePort port = toFluidPort("in_" + (inIdx++), PortDirection.INPUT, fluidIng, content, fluidInChances[i]);
                if (port != null) {
                    inputs.add(port);
                }
            }
        }

        List<Content> itemOutContents = recipe.getOutputContents(ItemRecipeCapability.CAP);
        ChanceLogic itemOutLogic = getChanceLogic(recipe, ItemRecipeCapability.CAP, IO.OUT);
        double[] itemOutChances = computeEffectiveChances(itemOutContents, itemOutLogic);

        int outIdx = 0;
        for (int i = 0; i < itemOutContents.size(); i++) {
            Content content = itemOutContents.get(i);
            if (content.content instanceof Ingredient ing) {
                ResourcePort port = toItemPort("out_" + (outIdx++), PortDirection.OUTPUT, ing, content, itemOutChances[i]);
                if (port != null) {
                    outputs.add(port);
                }
            }
        }

        List<Content> fluidOutContents = recipe.getOutputContents(FluidRecipeCapability.CAP);
        ChanceLogic fluidOutLogic = getChanceLogic(recipe, FluidRecipeCapability.CAP, IO.OUT);
        double[] fluidOutChances = computeEffectiveChances(fluidOutContents, fluidOutLogic);

        for (int i = 0; i < fluidOutContents.size(); i++) {
            Content content = fluidOutContents.get(i);
            if (content.content instanceof FluidIngredient fluidIng) {
                ResourcePort port = toFluidPort("out_" + (outIdx++), PortDirection.OUTPUT, fluidIng, content, fluidOutChances[i]);
                if (port != null) {
                    outputs.add(port);
                }
            }
        }

        if (inputs.isEmpty() && outputs.isEmpty()) {
            return null;
        }

        long eut = resolveSignedEut(recipe);
        int tier = VoltageTier.fromEut(eut).index();
        int duration = Math.max(1, recipe.duration);
        int coilTemp = recipe.data != null && recipe.data.contains("ebf_temp") ? recipe.data.getInt("ebf_temp") : 0;
        boolean cleanroom = hasCleanroomCondition(recipe);

        String typeId = recipe.recipeType != null && recipe.recipeType.registryName != null
                ? recipe.recipeType.registryName.toString()
                : "gtceu:unknown";
        String machineLabel = prettyMachineName(typeId);
        boolean perfectOc = typeId.endsWith("large_chemical_reactor");

        String title = buildTitle(outputs, inputs, machineLabel);
        ResourceLocation rid = recipe.getId();

        return new RecipeEntry(
                rid.toString(),
                typeId,
                rid.getNamespace(),
                typeId,
                title,
                machineLabel,
                inputs,
                outputs,
                eut,
                duration,
                tier,
                circuit,
                coilTemp,
                cleanroom,
                perfectOc,
                1,
                null
        );
    }

    private static @Nullable Integer tryExtractCircuit(Ingredient ing) {
        Ingredient inner = SizedIngredient.getInner(ing);
        if (inner instanceof IntCircuitIngredient) {
            ItemStack[] stacks = inner.getItems();
            if (stacks.length > 0) {
                int cfg = IntCircuitBehaviour.getCircuitConfiguration(stacks[0]);
                return Math.max(0, Math.min(IntCircuitIngredient.CIRCUIT_MAX, cfg));
            }
            return 0;
        }
        ItemStack[] stacks = inner.getItems();
        if (stacks.length == 1 && IntCircuitBehaviour.isIntegratedCircuit(stacks[0])) {
            int cfg = IntCircuitBehaviour.getCircuitConfiguration(stacks[0]);
            if (cfg >= 0 && cfg <= IntCircuitIngredient.CIRCUIT_MAX) {
                return cfg;
            }
        }
        return null;
    }

    private static @Nullable ResourcePort toItemPort(
            String portId,
            PortDirection dir,
            Ingredient ing,
            Content content,
            double effectiveChance
    ) {
        int amount = 1;
        if (ing instanceof SizedIngredient sized) {
            amount = Math.max(1, sized.getAmount());
        }
        Ingredient inner = SizedIngredient.getInner(ing);

        boolean catalyst = dir == PortDirection.INPUT && content.chance == 0;
        double chance = catalyst ? 1.0 : effectiveChance;
        double boost = content.maxChance > 0 ? (double) content.tierChanceBoost / content.maxChance : 0.0;

        String tagId = extractItemTagId(inner);
        if (tagId != null) {
            return new ResourcePort(
                    portId,
                    dir,
                    ResourceKey.tag(tagId),
                    amount,
                    chance,
                    boost,
                    catalyst,
                    null
            );
        }

        ItemStack[] stacks = inner.getItems();
        if (stacks.length == 0) {
            return null;
        }
        ItemStack first = stacks[0];
        if (amount == 1 && first.getCount() > 1) {
            amount = first.getCount();
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(first.getItem());
        if (itemId == null) {
            return null;
        }

        return new ResourcePort(
                portId,
                dir,
                ResourceKey.item(itemId.toString()),
                amount,
                chance,
                boost,
                catalyst,
                first.getHoverName().getString()
        );
    }

    private static @Nullable String extractItemTagId(Ingredient inner) {
        try {
            JsonElement json = inner.toJson();
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                if (obj.has("tag") && obj.get("tag").isJsonPrimitive()) {
                    return obj.get("tag").getAsString();
                }
            }
        } catch (Throwable ignored) {
            // Non-serializable custom ingredients fall back to getItems()
        }
        return null;
    }

    private static @Nullable ResourcePort toFluidPort(
            String portId,
            PortDirection dir,
            FluidIngredient ing,
            Content content,
            double effectiveChance
    ) {
        int amount = Math.max(1, ing.getAmount());
        boolean catalyst = dir == PortDirection.INPUT && content.chance == 0;
        double chance = catalyst ? 1.0 : effectiveChance;
        double boost = content.maxChance > 0 ? (double) content.tierChanceBoost / content.maxChance : 0.0;

        if (ing.values != null && ing.values.length == 1 && ing.values[0] instanceof FluidIngredient.TagValue tagValue) {
            ResourceLocation tagLoc = tagValue.tag().location();
            return new ResourcePort(
                    portId,
                    dir,
                    ResourceKey.tag(tagLoc.toString()),
                    amount,
                    chance,
                    boost,
                    catalyst,
                    null
            );
        }

        FluidStack[] stacks = ing.getStacks();
        if (stacks == null || stacks.length == 0) {
            return null;
        }
        FluidStack first = stacks[0];
        ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(first.getFluid());
        if (fluidId == null) {
            return null;
        }

        return new ResourcePort(
                portId,
                dir,
                ResourceKey.fluid(fluidId.toString()),
                amount,
                chance,
                boost,
                catalyst,
                first.getDisplayName().getString()
        );
    }

    private static ChanceLogic getChanceLogic(GTRecipe recipe, RecipeCapability<?> cap, IO io) {
        try {
            ChanceLogic logic = recipe.getChanceLogicForCapability(cap, io, false);
            return logic != null ? logic : ChanceLogic.OR;
        } catch (Throwable ignored) {
            return ChanceLogic.OR;
        }
    }

    private static double[] computeEffectiveChances(List<Content> contents, ChanceLogic logic) {
        double[] result = new double[contents.size()];
        if (contents.isEmpty()) {
            return result;
        }

        if (logic == ChanceLogic.NONE) {
            java.util.Arrays.fill(result, 1.0);
            return result;
        }

        if (logic == ChanceLogic.XOR) {
            double chancedSum = 0.0;
            for (Content c : contents) {
                if (c.maxChance > 0 && c.chance > 0 && c.chance < c.maxChance) {
                    chancedSum += (double) c.chance / c.maxChance;
                }
            }
            double norm = chancedSum > 1.0 ? chancedSum : 1.0;
            for (int i = 0; i < contents.size(); i++) {
                Content c = contents.get(i);
                if (c.maxChance > 0 && c.chance > 0 && c.chance < c.maxChance) {
                    result[i] = Math.min(1.0, ((double) c.chance / c.maxChance) / norm);
                } else {
                    result[i] = 1.0;
                }
            }
            return result;
        }

        if (logic == ChanceLogic.FIRST) {
            double remainingProb = 1.0;
            for (int i = 0; i < contents.size(); i++) {
                Content c = contents.get(i);
                if (c.maxChance > 0 && c.chance > 0 && c.chance < c.maxChance) {
                    double p = Math.min(1.0, (double) c.chance / c.maxChance);
                    result[i] = remainingProb * p;
                    remainingProb *= (1.0 - p);
                } else {
                    result[i] = 1.0;
                }
            }
            return result;
        }

        // ChanceLogic.OR and ChanceLogic.AND both have marginal probability chance / maxChance
        for (int i = 0; i < contents.size(); i++) {
            Content c = contents.get(i);
            result[i] = c.maxChance > 0 && c.chance > 0
                    ? Math.min(1.0, (double) c.chance / c.maxChance)
                    : 1.0;
        }
        return result;
    }

    private static long resolveSignedEut(GTRecipe recipe) {
        EnergyStack.WithIO eutIo = RecipeHelper.getRealEUtWithIO(recipe);
        if (eutIo != null && !eutIo.isEmpty()) {
            long total = eutIo.getTotalEU();
            if (total > 0L) {
                return eutIo.isOutput() ? -total : total;
            }
        }
        EnergyStack inEu = RecipeHelper.getRealEUt(recipe);
        long total = inEu != null ? inEu.getTotalEU() : 0L;
        if (total == 0L) {
            return 0L;
        }
        boolean isGenerator = recipe.tickOutputs != null
                && recipe.tickOutputs.containsKey(EURecipeCapability.CAP)
                && !recipe.tickOutputs.get(EURecipeCapability.CAP).isEmpty()
                && (recipe.tickInputs == null || !recipe.tickInputs.containsKey(EURecipeCapability.CAP));
        return isGenerator ? -total : total;
    }

    private static boolean hasCleanroomCondition(GTRecipe recipe) {
        if (recipe.conditions == null) {
            return false;
        }
        for (RecipeCondition<?> condition : recipe.conditions) {
            if (condition instanceof CleanroomCondition) {
                return true;
            }
        }
        return false;
    }

    private static String buildTitle(List<ResourcePort> outputs, List<ResourcePort> inputs, String machineLabel) {
        if (!outputs.isEmpty()) {
            String suffix = outputs.size() > 1 ? " +" + (outputs.size() - 1) : "";
            return outputs.get(0).displayLabel() + suffix + " (" + machineLabel + ")";
        }
        if (!inputs.isEmpty()) {
            return inputs.get(0).displayLabel() + " (" + machineLabel + ")";
        }
        return machineLabel;
    }

    private static String prettyMachineName(String typeId) {
        int colon = typeId.indexOf(':');
        String path = colon >= 0 ? typeId.substring(colon + 1) : typeId;
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
