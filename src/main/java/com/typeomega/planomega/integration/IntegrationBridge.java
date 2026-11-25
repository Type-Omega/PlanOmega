package com.typeomega.planomega.integration;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.integration.ae2.Ae2Bridge;
import com.typeomega.planomega.integration.emi.EmiLookupHelper;
import com.typeomega.planomega.integration.emi.EmiTreeImporter;
import com.typeomega.planomega.integration.jei.JeiPlanOmegaPlugin;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.recipe.RecipeEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Safe reflection-free bridge to optional EMI, JEI, and AE2 integrations.
 */
public final class IntegrationBridge {

    private IntegrationBridge() {
    }

    public record XeiLookup(
            @Nullable String recipeId,
            @Nullable ResourceKey resourceKey,
            @Nullable RecipeEntry fallbackRecipe
    ) {
    }

    public static Optional<XeiLookup> readHoveredFromXei(Screen screen, double mouseX, double mouseY) {
        if (ModList.get().isLoaded("emi")) {
            try {
                Optional<XeiLookup> emi = EmiLookupHelper.getHovered(mouseX, mouseY);
                if (emi.isPresent()) {
                    return emi;
                }
            } catch (Throwable t) {
                PlanOmega.LOGGER.debug("EMI hovered lookup failed", t);
            }
        }
        if (ModList.get().isLoaded("jei")) {
            try {
                return JeiPlanOmegaPlugin.getHovered(screen, mouseX, mouseY);
            } catch (Throwable t) {
                PlanOmega.LOGGER.debug("JEI hovered lookup failed", t);
            }
        }
        return Optional.empty();
    }

    public static boolean hasActiveEmiTree() {
        if (!ModList.get().isLoaded("emi")) {
            return false;
        }
        try {
            return EmiTreeImporter.hasActiveTree();
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("EMI tree check failed", t);
            return false;
        }
    }

    public static Optional<PlanGraph> importActiveEmiTree() {
        if (!ModList.get().isLoaded("emi")) {
            return Optional.empty();
        }
        try {
            return EmiTreeImporter.importActiveTree();
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("EMI tree import failed", t);
            return Optional.empty();
        }
    }

    public static boolean enqueueActiveEmiTree() {
        if (!ModList.get().isLoaded("emi")) {
            return false;
        }
        try {
            return EmiTreeImporter.enqueueActiveTree();
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("EMI tree enqueue failed", t);
            return false;
        }
    }

    public static boolean isAe2Available() {
        return Ae2Bridge.isAvailable();
    }

    public static Ae2Bridge.Ae2NetworkSnapshot queryAe2Snapshot() {
        return Ae2Bridge.queryNearbyOrCached();
    }

    public static Map<String, Ae2Bridge.Ae2ResourceStatus> queryPlanAe2Status(PlanGraph graph) {
        return Ae2Bridge.queryPlanResources(graph);
    }

    public static Set<String> findAe2CraftableResources(PlanGraph graph) {
        return Ae2Bridge.findCraftableResources(graph);
    }
}
