package com.typeomega.planomega.recipe;

import com.google.gson.JsonElement;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.ResearchData;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.EnergyStack;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.recipe.GTCeuRecipeParsingHelpers.ChanceInput;
import com.typeomega.planomega.recipe.GTCeuRecipeParsingHelpers.ChanceMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        long fusionStartEu = recipe.data != null && recipe.data.contains("eu_to_start")
                ? recipe.data.getLong("eu_to_start")
                : 0L;
        int tier = Math.max(
                VoltageTier.fromEut(eut).index(),
                GTCeuRecipeParsingHelpers.computeFusionMinTier(fusionStartEu)
        );
        int duration = Math.max(1, recipe.duration);
        int coilTemp = recipe.data != null && recipe.data.contains("ebf_temp") ? recipe.data.getInt("ebf_temp") : 0;
        boolean cleanroom = hasCleanroomCondition(recipe);
        Set<String> researchIds = extractResearchIds(recipe);

        String typeId = recipe.recipeType != null && recipe.recipeType.registryName != null
                ? recipe.recipeType.registryName.toString()
                : "gtceu:unknown";
        String machineLabel = GTCeuRecipeParsingHelpers.prettyMachineName(typeId);
        boolean perfectOc = typeId.endsWith("large_chemical_reactor");
        boolean isAssemblyLine = typeId.endsWith("assembly_line") || !researchIds.isEmpty();
        boolean hasCatalyst = inputs.stream().anyMatch(ResourcePort::catalyst);

        String title = GTCeuRecipeParsingHelpers.buildTitle(outputs, inputs, machineLabel);
        ResourceLocation rid = recipe.getId();

        RecipeEntry baseEntry = new RecipeEntry(
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
        String extendedBlob = GTCeuRecipeParsingHelpers.buildExtendedSearchBlob(
                baseEntry.searchBlob(),
                researchIds,
                fusionStartEu,
                isAssemblyLine,
                hasCatalyst
        );
        return new RecipeEntry(
                baseEntry.recipeId(),
                baseEntry.recipeTypeId(),
                baseEntry.sourceMod(),
                baseEntry.machineId(),
                baseEntry.title(),
                baseEntry.machineLabel(),
                baseEntry.inputs(),
                baseEntry.outputs(),
                baseEntry.eut(),
                baseEntry.durationTicks(),
                baseEntry.tier(),
                baseEntry.circuit(),
                baseEntry.coilTemp(),
                baseEntry.cleanroom(),
                baseEntry.perfectOverclock(),
                baseEntry.parallelLimit(),
                extendedBlob
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

        boolean catalyst = GTCeuRecipeParsingHelpers.isCatalystInput(dir, content.chance);
        double chance = catalyst ? 1.0 : effectiveChance;
        double boost = GTCeuRecipeParsingHelpers.computeTierChanceBoost(content.tierChanceBoost, content.maxChance);

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
            return GTCeuRecipeParsingHelpers.extractTagFromJson(json);
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
        boolean catalyst = GTCeuRecipeParsingHelpers.isCatalystInput(dir, content.chance);
        double chance = catalyst ? 1.0 : effectiveChance;
        double boost = GTCeuRecipeParsingHelpers.computeTierChanceBoost(content.tierChanceBoost, content.maxChance);

        if (ing.values != null && ing.values.length > 0 && ing.values[0] instanceof FluidIngredient.TagValue tagValue) {
            FluidStack[] stacks = ing.getStacks();
            if (ing.values.length == 1 || stacks == null || stacks.length == 0) {
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
        List<ChanceInput> inputs = new ArrayList<>(contents.size());
        for (Content c : contents) {
            inputs.add(new ChanceInput(c.chance, c.maxChance, c.tierChanceBoost));
        }
        ChanceMode mode;
        if (logic == ChanceLogic.NONE) {
            mode = ChanceMode.NONE;
        } else if (logic == ChanceLogic.XOR) {
            mode = ChanceMode.XOR;
        } else if (logic == ChanceLogic.FIRST) {
            mode = ChanceMode.FIRST;
        } else if (logic == ChanceLogic.AND) {
            mode = ChanceMode.AND;
        } else {
            mode = ChanceMode.OR;
        }
        return GTCeuRecipeParsingHelpers.computeEffectiveChances(inputs, mode);
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

    private static Set<String> extractResearchIds(GTRecipe recipe) {
        if (recipe.conditions == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (RecipeCondition<?> condition : recipe.conditions) {
            if (condition instanceof ResearchCondition research && research.getData() != null) {
                for (ResearchData.ResearchEntry entry : research.getData()) {
                    if (entry.getResearchId() != null && !entry.getResearchId().isBlank()) {
                        ids.add(entry.getResearchId());
                    }
                }
            }
        }
        return ids;
    }
}
