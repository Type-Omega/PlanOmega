package com.typeomega.planomega;

import com.mojang.logging.LogUtils;
import com.typeomega.planomega.client.PlanOmegaClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;

/**
 * Entry point for Plan Omega (100% client-side visual production planner and recipe engineering tool).
 */
@Mod(PlanOmega.MOD_ID)
@SuppressWarnings("removal")
public final class PlanOmega {

    public static final String MOD_ID = "planomega";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PlanOmega() {
        ModLoadingContext.get().registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true)
        );

        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.info("Plan Omega is client-side only; skipping initialization on dedicated server.");
            return;
        }

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        PlanOmegaClient.init(modBus);
        LOGGER.info("Plan Omega client initialized.");
    }
}
