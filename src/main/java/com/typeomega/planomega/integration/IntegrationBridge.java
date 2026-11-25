package com.typeomega.planomega.integration;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.integration.emi.EmiLookupHelper;
import com.typeomega.planomega.integration.jei.JeiPlanOmegaPlugin;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.recipe.RecipeEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Safe reflection-free bridge to optional EMI and JEI integrations.
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
}
