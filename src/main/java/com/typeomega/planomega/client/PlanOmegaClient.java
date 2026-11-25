package com.typeomega.planomega.client;

import com.typeomega.planomega.client.ui.PlannerScreen;
import com.typeomega.planomega.integration.IntegrationBridge;
import com.typeomega.planomega.recipe.RecipeCatalogManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Optional;

/**
 * Client-only initialization: registers keybindings, recipe catalog invalidation,
 * and EMI/JEI hovered lookup shortcut.
 */
public final class PlanOmegaClient {

    private PlanOmegaClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(PlanOmegaKeybinds::register);
        MinecraftForge.EVENT_BUS.addListener(PlanOmegaClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(PlanOmegaClient::onScreenKeyPressed);
        RecipeCatalogManager.registerEvents();
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        while (PlanOmegaKeybinds.OPEN_PLANNER.consumeClick()) {
            if (!(mc.screen instanceof PlannerScreen)) {
                mc.setScreen(new PlannerScreen());
            }
        }
    }

    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof PlannerScreen) {
            return;
        }
        if (!PlanOmegaKeybinds.ADD_FROM_XEI.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / Math.max(1, mc.getWindow().getScreenWidth());
        double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / Math.max(1, mc.getWindow().getScreenHeight());

        Optional<IntegrationBridge.XeiLookup> lookup = IntegrationBridge.readHoveredFromXei(event.getScreen(), mx, my);
        if (lookup.isPresent()) {
            event.setCanceled(true);
            PlannerScreen planner = new PlannerScreen();
            mc.setScreen(planner);
            planner.handleXeiLookup(lookup.get());
        }
    }
}
