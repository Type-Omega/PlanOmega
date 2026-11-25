package com.typeomega.planomega.integration.jei;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.integration.IntegrationBridge.XeiLookup;
import com.typeomega.planomega.model.ResourceKey;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * JEI plugin for Plan Omega, capturing the active {@link IJeiRuntime} to inspect hovered items and fluids.
 */
@JeiPlugin
public final class JeiPlanOmegaPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(PlanOmega.MOD_ID, "jei_plugin");
    private static volatile @Nullable IJeiRuntime jeiRuntime;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
    }

    @Override
    public void onRuntimeUnavailable() {
        jeiRuntime = null;
    }

    @SuppressWarnings("removal")
    public static Optional<XeiLookup> getHovered(Screen screen, double mouseX, double mouseY) {
        IJeiRuntime runtime = jeiRuntime;
        if (runtime == null) {
            return Optional.empty();
        }
        if (runtime.getIngredientListOverlay().hasKeyboardFocus()) {
            return Optional.empty();
        }
        Optional<ITypedIngredient<?>> hovered = runtime.getIngredientListOverlay().getIngredientUnderMouse()
                .or(() -> runtime.getBookmarkOverlay().getIngredientUnderMouse());

        if (hovered.isEmpty() && screen != null) {
            hovered = runtime.getScreenHelper()
                    .getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                    .findFirst()
                    .map(IClickableIngredient::getTypedIngredient);
        }

        return hovered.flatMap(JeiPlanOmegaPlugin::toKey)
                .map(key -> new XeiLookup(null, key, null));
    }

    private static Optional<ResourceKey> toKey(ITypedIngredient<?> ingredient) {
        Optional<ItemStack> itemOpt = ingredient.getItemStack();
        if (itemOpt.isPresent() && !itemOpt.get().isEmpty()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemOpt.get().getItem());
            if (id != null) {
                return Optional.of(ResourceKey.item(id.toString()));
            }
        }
        Optional<FluidStack> fluidOpt = ingredient.getIngredient(ForgeTypes.FLUID_STACK);
        if (fluidOpt.isPresent() && !fluidOpt.get().isEmpty()) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluidOpt.get().getFluid());
            if (id != null) {
                return Optional.of(ResourceKey.fluid(id.toString()));
            }
        }
        return Optional.empty();
    }
}
