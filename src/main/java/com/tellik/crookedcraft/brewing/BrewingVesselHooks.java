package com.tellik.crookedcraft.brewing;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Small, focused hooks invoked from different entry points
 * (e.g., CauldronInteraction shim, future automation hooks).
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class BrewingVesselHooks {
    private BrewingVesselHooks() {}

    /**
     * Called after cauldron interactions that may have changed the cauldron contents/level.

     * If the block at pos is a valid brewing vessel and is currently a WATER_CAULDRON,
     * we ensure it is tracked in BrewingVesselData so the tick loop can heat/boil without
     * requiring any follow-up player interaction.
     */
    public static void onMaybeWaterCauldronChanged(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (!state.is(ModTags.Blocks.BREW_VESSELS)) return;
        if (!state.is(Blocks.WATER_CAULDRON)) return;

        BrewingVesselData data = BrewingVesselData.get(level);
        long key = pos.asLong();

        data.ensureTracked(key);
        BrewingVesselData.VesselState v = data.getTrackedState(key);

        // Seed boil requirement if heat exists; this makes status readouts accurate immediately
        Integer req = HeatSourceManager.getBoilTicksFor(level, pos);
        if (req != null && v.boilTicksRequired <= 0) {
            v.boilTicksRequired = req;
        }

        data.setDirty();
    }
}
