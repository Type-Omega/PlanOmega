package com.typeomega.planomega.integration.jei;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.integration.IntegrationBridge.XeiLookup;
import com.typeomega.planomega.integration.PendingRecipeQueue;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.recipe.RecipeCatalogManager;
import com.typeomega.planomega.recipe.RecipeEntry;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI plugin for Plan Omega, capturing {@link IJeiRuntime} to inspect hovered items/fluids
 * and adding a "Send to Plan Omega" button on JEI recipe screens.
 */
@JeiPlugin
public final class JeiPlanOmegaPlugin implements IModPlugin {

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(PlanOmega.MOD_ID, "jei_plugin");
    private static volatile @Nullable IJeiRuntime jeiRuntime;
    private static volatile boolean eventsRegistered = false;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
        if (!eventsRegistered) {
            eventsRegistered = true;
            MinecraftForge.EVENT_BUS.addListener(JeiPlanOmegaPlugin::onScreenInitPost);
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        jeiRuntime = null;
    }

    private static void onScreenInitPost(ScreenEvent.Init.Post event) {
        IJeiRuntime runtime = jeiRuntime;
        Screen screen = event.getScreen();
        if (runtime == null || !(screen instanceof IRecipesGui)) {
            return;
        }
        Optional<IGuiProperties> propsOpt = runtime.getScreenHelper().getGuiProperties(screen);
        int btnW = 24;
        int btnH = 18;
        int bx;
        int by;
        if (propsOpt.isPresent()) {
            IGuiProperties props = propsOpt.get();
            bx = props.getGuiLeft() + props.getGuiXSize() - btnW;
            by = props.getGuiTop() >= btnH + 4 ? props.getGuiTop() - btnH - 2 : props.getGuiTop() + 4;
        } else {
            bx = Math.max(4, screen.width / 2 + 64);
            by = 8;
        }

        Button sendBtn = Button.builder(
                        Component.translatable("planomega.xei.send_button_short"),
                        btn -> {
                            Minecraft mc = Minecraft.getInstance();
                            double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth()
                                    / Math.max(1, mc.getWindow().getScreenWidth());
                            double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight()
                                    / Math.max(1, mc.getWindow().getScreenHeight());
                            enqueueDisplayedRecipe(screen, mx, my);
                        })
                .bounds(bx, by, btnW, btnH)
                .tooltip(Tooltip.create(Component.translatable("planomega.xei.send_to_planner")))
                .build();
        event.addListener(sendBtn);
    }

    /**
     * Enqueues the hovered (or first visible) recipe from a JEI {@link IRecipesGui} screen
     * into {@link PendingRecipeQueue}.
     */
    public static boolean enqueueDisplayedRecipe(Screen screen, double mouseX, double mouseY) {
        if (!(screen instanceof IRecipesGui)) {
            return false;
        }
        List<IRecipeLayoutDrawable<?>> layouts = extractRecipeLayouts(screen);
        if (layouts.isEmpty()) {
            return false;
        }
        IRecipeLayoutDrawable<?> target = null;
        for (IRecipeLayoutDrawable<?> layout : layouts) {
            if (layout.isMouseOver(mouseX, mouseY)) {
                target = layout;
                break;
            }
        }
        if (target == null) {
            target = layouts.get(0);
        }
        Optional<RecipeEntry> entryOpt = resolveOrConvertLayout(target);
        if (entryOpt.isEmpty()) {
            return false;
        }
        RecipeEntry entry = entryOpt.get();
        PendingRecipeQueue.enqueue(entry);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("planomega.xei.enqueued_recipe", entry.title()),
                    true
            );
        }
        return true;
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

        if (screen instanceof IRecipesGui) {
            for (IRecipeLayoutDrawable<?> layout : extractRecipeLayouts(screen)) {
                if (layout.isMouseOver(mouseX, mouseY)) {
                    Optional<RecipeEntry> resolved = resolveOrConvertLayout(layout);
                    if (resolved.isPresent()) {
                        RecipeEntry entry = resolved.get();
                        ResourceKey outKey = entry.outputs().isEmpty() ? null : entry.outputs().get(0).resource();
                        return Optional.of(new XeiLookup(entry.recipeId(), outKey, entry));
                    }
                }
            }
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

    private static List<IRecipeLayoutDrawable<?>> extractRecipeLayouts(Screen screen) {
        List<IRecipeLayoutDrawable<?>> result = new ArrayList<>();
        try {
            Field layoutsField = screen.getClass().getDeclaredField("layouts");
            layoutsField.setAccessible(true);
            Object layoutsObj = layoutsField.get(screen);
            if (layoutsObj == null) {
                return result;
            }
            Field listField = layoutsObj.getClass().getDeclaredField("recipeLayoutsWithButtons");
            listField.setAccessible(true);
            Object rawList = listField.get(layoutsObj);
            if (rawList instanceof List<?> items) {
                for (Object item : items) {
                    if (item == null) {
                        continue;
                    }
                    Object drawable = item.getClass().getMethod("recipeLayout").invoke(item);
                    if (drawable instanceof IRecipeLayoutDrawable<?> layout) {
                        result.add(layout);
                    }
                }
            }
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("Could not inspect JEI recipe layouts", t);
        }
        return result;
    }

    private static <R> Optional<RecipeEntry> resolveOrConvertLayout(IRecipeLayoutDrawable<R> layout) {
        IRecipeCategory<R> category = layout.getRecipeCategory();
        R recipe = layout.getRecipe();
        ResourceLocation registryName = category.getRegistryName(recipe);
        if (registryName != null) {
            Optional<RecipeEntry> catalogMatch = RecipeCatalogManager.get().findById(registryName.toString());
            if (catalogMatch.isPresent()) {
                return catalogMatch;
            }
        }

        List<ResourcePort> inputs = new ArrayList<>();
        List<ResourcePort> outputs = new ArrayList<>();
        int inIdx = 0;
        int outIdx = 0;

        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            Optional<ITypedIngredient<?>> displayed = slot.getDisplayedIngredient();
            if (displayed.isEmpty()) {
                continue;
            }
            ITypedIngredient<?> ing = displayed.get();
            Optional<ResourceKey> keyOpt = toKey(ing);
            if (keyOpt.isEmpty()) {
                continue;
            }
            ResourceKey key = keyOpt.get();
            long amount = extractAmount(ing);
            String label = extractLabel(ing, key);
            RecipeIngredientRole role = slot.getRole();
            if (role == RecipeIngredientRole.INPUT || role == RecipeIngredientRole.CATALYST) {
                inputs.add(new ResourcePort(
                        "in_" + (inIdx++),
                        PortDirection.INPUT,
                        key,
                        amount,
                        1.0,
                        0.0,
                        role == RecipeIngredientRole.CATALYST,
                        label
                ));
            } else if (role == RecipeIngredientRole.OUTPUT) {
                outputs.add(new ResourcePort(
                        "out_" + (outIdx++),
                        PortDirection.OUTPUT,
                        key,
                        amount,
                        1.0,
                        0.0,
                        false,
                        label
                ));
            }
        }

        if (inputs.isEmpty() && outputs.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation catUid = category.getRecipeType().getUid();
        String recipeId = registryName != null ? registryName.toString() : catUid + "/jei_" + Math.abs(recipe.hashCode());
        String catId = catUid.toString();
        String title = !outputs.isEmpty() ? outputs.get(0).displayLabel() : category.getTitle().getString();
        return Optional.of(new RecipeEntry(
                recipeId,
                catId,
                catUid.getNamespace(),
                null,
                title,
                category.getTitle().getString(),
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
        ));
    }

    private static long extractAmount(ITypedIngredient<?> ingredient) {
        Optional<ItemStack> itemOpt = ingredient.getItemStack();
        if (itemOpt.isPresent() && !itemOpt.get().isEmpty()) {
            return Math.max(1L, itemOpt.get().getCount());
        }
        Optional<FluidStack> fluidOpt = ingredient.getIngredient(ForgeTypes.FLUID_STACK);
        if (fluidOpt.isPresent() && !fluidOpt.get().isEmpty()) {
            return Math.max(1L, fluidOpt.get().getAmount());
        }
        return 1L;
    }

    private static String extractLabel(ITypedIngredient<?> ingredient, ResourceKey fallback) {
        Optional<ItemStack> itemOpt = ingredient.getItemStack();
        if (itemOpt.isPresent() && !itemOpt.get().isEmpty()) {
            return itemOpt.get().getHoverName().getString();
        }
        Optional<FluidStack> fluidOpt = ingredient.getIngredient(ForgeTypes.FLUID_STACK);
        if (fluidOpt.isPresent() && !fluidOpt.get().isEmpty()) {
            return fluidOpt.get().getDisplayName().getString();
        }
        return fallback.shortName();
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
