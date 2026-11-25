package com.typeomega.planomega.recipe;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.client.ui.ItemFluidIconRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Lazily builds and caches the client-side {@link RecipeCatalog} from the active {@link RecipeManager},
 * invalidating automatically on server disconnect or recipe datapack sync.
 */
public final class RecipeCatalogManager {

    private static volatile RecipeCatalog cachedCatalog;
    private static volatile RecipeManager lastManager;

    private RecipeCatalogManager() {
    }

    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((RecipesUpdatedEvent event) -> invalidate());
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> invalidate());
    }

    public static void invalidate() {
        cachedCatalog = null;
        lastManager = null;
        ItemFluidIconRenderer.clearCache();
    }

    public static RecipeCatalog get() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return RecipeCatalog.EMPTY;
        }
        RecipeManager manager = level.getRecipeManager();
        if (cachedCatalog != null && lastManager == manager) {
            return cachedCatalog;
        }
        synchronized (RecipeCatalogManager.class) {
            if (cachedCatalog != null && lastManager == manager) {
                return cachedCatalog;
            }
            long start = System.nanoTime();
            boolean gtLoaded = ModList.get().isLoaded("gtceu");
            List<RecipeEntry> all = new ArrayList<>();
            if (gtLoaded) {
                try {
                    all.addAll(GTCeuRecipeExtractor.extract(manager));
                } catch (Throwable t) {
                    PlanOmega.LOGGER.error("Failed to extract GTCEu recipes for Plan Omega", t);
                }
            }
            try {
                all.addAll(VanillaRecipeExtractor.extract(manager, level.registryAccess(), gtLoaded));
            } catch (Throwable t) {
                PlanOmega.LOGGER.error("Failed to extract vanilla/custom recipes for Plan Omega", t);
            }

            cachedCatalog = new RecipeCatalog(all);
            lastManager = manager;
            long ms = (System.nanoTime() - start) / 1_000_000L;
            PlanOmega.LOGGER.info("Indexed {} recipes into Plan Omega catalog in {} ms", cachedCatalog.size(), ms);
            return cachedCatalog;
        }
    }
}
