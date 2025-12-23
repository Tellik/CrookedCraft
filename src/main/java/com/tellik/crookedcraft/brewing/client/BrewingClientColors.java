package com.tellik.crookedcraft.brewing.client;

import com.tellik.crookedcraft.CrookedCraft;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import com.tellik.crookedcraft.brewing.ModBrewingItems;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CrookedCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BrewingClientColors {
    private BrewingClientColors() {}

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> {
            if (tintIndex != 0) return 0xFFFFFFFF;

            BrewWaterCauldronBlock.BrewState brewState = state.getValue(BrewWaterCauldronBlock.BREW_STATE);

            if (brewState == BrewWaterCauldronBlock.BrewState.NONE) {
                if (level == null || pos == null) return 0xFF3F76E4;
                return 0xFF000000 | BiomeColors.getAverageWaterColor(level, pos);
            }

            if (brewState == BrewWaterCauldronBlock.BrewState.COMPLETE) {
                return 0xFF66FF66;
            }

            return 0xFF101010;
        }, ModBrewingBlocks.BREW_WATER_CAULDRON.get());
    }

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        // With the model:
        // layer0 = potion_overlay (bottle/outline) -> NOT tinted
        // layer1 = potion (liquid) -> tinted black
        event.register((stack, tintIndex) -> {
            if (tintIndex == 1) return 0xFF000000; // black liquid
            return 0xFFFFFFFF;                    // normal bottle/overlay
        }, ModBrewingItems.BLACK_SLUDGE.get());
    }
}
