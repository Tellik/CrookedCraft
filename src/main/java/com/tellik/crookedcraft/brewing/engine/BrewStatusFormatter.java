package com.tellik.crookedcraft.brewing.engine;

import com.tellik.crookedcraft.brewing.BrewingVesselData;
import com.tellik.crookedcraft.brewing.HeatSourceManager;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import com.tellik.crookedcraft.brewing.ModTags;
import com.tellik.crookedcraft.brewing.ThermalTransformManager;
import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class BrewStatusFormatter {
    private BrewStatusFormatter() {}

    public static void sendStatus(net.minecraft.world.entity.player.Player player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModTags.Blocks.BREW_VESSELS)) return;

        player.displayClientMessage(Component.literal("=== CrookedCraft Brewing ==="), false);

        boolean isWater = state.getBlock() instanceof BrewWaterCauldronBlock;
        boolean isSnow  = state.getBlock() instanceof BrewPowderSnowCauldronBlock;
        boolean isLava  = state.getBlock() instanceof BrewLavaCauldronBlock || state.is(ModBrewingBlocks.BREW_LAVA_CAULDRON.get());

        int lvl = getCauldronLevel(state);

        String vesselName =
                isWater ? ("Water " + lvl + "/3") :
                        isSnow  ? ("PowderSnow " + lvl + "/3") :
                                isLava  ? "Lava" :
                                        state.is(ModBrewingBlocks.BREW_CAULDRON.get()) ? "Empty" :
                                                "Unknown";

        player.displayClientMessage(Component.literal("Vessel: Brew Cauldron (" + vesselName + ")"), false);

        HeatSourceManager.HeatInfo heatInfo = HeatSourceManager.getHeatInfo(level, pos);
        player.displayClientMessage(Component.literal("Heat: " + (heatInfo != null ? heatInfo.asDisplayString() : "None")), false);

        BrewingVesselData data = BrewingVesselData.get(level);
        BrewingVesselData.VesselState v = data.getStateIfTracked(pos.asLong());

        if (v == null || Float.isNaN(v.tempC)) {
            player.displayClientMessage(Component.literal("Temp: (untracked)"), false);
        } else {
            player.displayClientMessage(Component.literal(String.format("Temp: %.2fC", v.tempC)), false);

            if (heatInfo != null) {
                float strength = getStrengthBonus(lvl);
                float speed = getSpeedBonus(lvl);

                float target = heatInfo.profile.maxTempC() * strength;
                float step = heatInfo.profile.heatPerTickC() * speed;

                player.displayClientMessage(Component.literal(String.format("Target: %.2fC (strength x%.2f)", target, strength)), false);
                player.displayClientMessage(Component.literal(String.format("Rate: %.3fC/t (speed x%.2f)", step, speed)), false);
            }
        }

        // Phase / transitions
        if (isLava) {
            ThermalTransformManager.CoolingTransform t =
                    ThermalTransformManager.getCoolingTransformFor(state.getBlock());

            if (t != null) {
                String setBlock = shortId(t.setBlockId());
                String drop = "(none)";
                if (t.drop() != null) {
                    drop = shortId(t.drop().itemId()) + " x" + Math.max(1, t.drop().count());
                }

                player.displayClientMessage(
                        Component.literal(String.format(
                                "Cooling Transform: <= %.1fC -> set %s, drop %s",
                                t.atOrBelowTempC(), setBlock, drop
                        )),
                        false
                );
            } else {
                player.displayClientMessage(Component.literal("Cooling Transform: (none)"), false);
            }
        } else if (isSnow) {
            player.displayClientMessage(Component.literal("Phase: FROZEN (melts at 0C -> water)"), false);
        } else if (isWater && v != null && !Float.isNaN(v.tempC)) {
            player.displayClientMessage(Component.literal(String.format("Boiling: %s (>= 100.0C)", (v.tempC >= 100.0f ? "YES" : "NO"))), false);
        }

        // Brewing state summary (water only)
        if (!isWater) return;

        if (v == null) {
            player.displayClientMessage(Component.literal("Brew: (untracked)"), false);
            return;
        }

        if (v.matchedRecipeId != null) {
            player.displayClientMessage(Component.literal("Brew: COMPLETE (" + v.matchedRecipeId + ")"), false);
        } else if (v.doomed) {
            player.displayClientMessage(Component.literal("Brew: DOOMED"), false);
        } else if (v.ingredients.isEmpty()) {
            player.displayClientMessage(Component.literal("Brew: empty"), false);
        } else {
            player.displayClientMessage(Component.literal("Brew: mixing (" + v.ingredients.size() + " ingredients)"), false);
        }
    }

    private static int getCauldronLevel(BlockState state) {
        if (state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) {
            return state.getValue(BlockStateProperties.LEVEL_CAULDRON);
        }
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        return 0;
    }

    // Strength: max temp boost for low volume
    private static float getStrengthBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.25f;
        if (cauldronLevel == 2) return 1.10f;
        return 1.0f;
    }

    // Speed: approach rate boost for low volume (requested)
    private static float getSpeedBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.66f;
        if (cauldronLevel == 2) return 1.33f;
        return 1.0f;
    }

    private static String shortId(ResourceLocation id) {
        if (id == null) return "unknown";
        return id.getNamespace() + ":" + id.getPath();
    }
}
