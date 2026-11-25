package com.typeomega.planomega.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybindings for Plan Omega.
 */
public final class PlanOmegaKeybinds {

    public static final String CATEGORY = "key.categories.planomega";
    public static final KeyMapping OPEN_PLANNER = new KeyMapping(
            "key.planomega.open_planner",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            CATEGORY
    );

    public static final KeyMapping ADD_FROM_XEI = new KeyMapping(
            "key.planomega.add_from_xei",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            CATEGORY
    );

    private PlanOmegaKeybinds() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PLANNER);
        event.register(ADD_FROM_XEI);
    }
}
