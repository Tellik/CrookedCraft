package com.tellik.crookedcraft.brewing.client;

import com.tellik.crookedcraft.brewing.ModBrewingBlockEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BrewingClientSetup {

    private BrewingClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBrewingBlockEntities.BREW_VESSEL.get(), BrewVesselRenderer::new);
    }
}
