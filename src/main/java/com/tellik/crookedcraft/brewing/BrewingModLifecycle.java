package com.tellik.crookedcraft.brewing;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Small MOD-bus lifecycle hook for Brewing systems.
 * Keeps Brewing initialization isolated and minimal.
 */
@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BrewingModLifecycle {
    private BrewingModLifecycle() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // enqueueWork ensures we're on the correct thread for touching vanilla registries/maps safely.
        event.enqueueWork(CauldronInteractionShim::install);
    }
}
