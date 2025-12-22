package com.tellik.crookedcraft.brewing.client;

import com.tellik.crookedcraft.CrookedCraft;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
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
            // Only tint the liquid surface layer
            if (tintIndex != 0) return 0xFFFFFFFF;

            BrewWaterCauldronBlock.BrewState brewState = state.getValue(BrewWaterCauldronBlock.BREW_STATE);

            // Default: normal biome-tinted water
            if (brewState == BrewWaterCauldronBlock.BrewState.NONE) {
                if (level == null || pos == null) {
                    return 0xFF3F76E4; // vanilla fallback-ish
                }
                return 0xFF000000 | BiomeColors.getAverageWaterColor(level, pos);
            }

            // COMPLETE/DOOMED colors are intentionally simple for now.
            // In a later milestone we can tint by resulting potion color (needs synced recipe/result info).
            if (brewState == BrewWaterCauldronBlock.BrewState.COMPLETE) {
                return 0xFF66FF66; // green-ish success tint (placeholder)
            }

            // DOOMED
            return 0xFF101010; // near-black ruined tint
        }, ModBrewingBlocks.BREW_WATER_CAULDRON.get());
    }
}
