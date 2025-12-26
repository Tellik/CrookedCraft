package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class BrewingVesselHooks {
    private BrewingVesselHooks() {}

    // Thermal defaults (keep in sync with your sim)
    private static final float AMBIENT_TEMP_C = 12.0f;
    private static final float SNOW_BASE_TEMP_C = -10.0f;
    private static final float LAVA_TEMP_C = 1000.0f;

    /**
     * Called AFTER vanilla cauldron interactions run.
     * If vanilla produced a vanilla water/lava/powder-snow cauldron, we replace it with our brew variant,
     * preserving level where applicable, and ensure the vessel is tracked.
     *
     * This is what prevents "powder snow bucket converts to vanilla cauldron".
     */
    public static void onAfterVanillaCauldronInteraction(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);

        // Vanilla WATER_CAULDRON -> Brew water
        if (s.is(Blocks.WATER_CAULDRON)) {
            int lvl = s.getValue(LayeredCauldronBlock.LEVEL);

            BlockState brew = ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState()
                    .setValue(LayeredCauldronBlock.LEVEL, lvl)
                    .setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.NONE);

            // Replace vanilla with brew variant
            level.setBlock(pos, brew, 3);

            // Track + thermal init. Reset brew state is safe (vanilla never had one).
            ensureThermalTracked(level, pos, /*resetBrew=*/true);
            return;
        }

        // Vanilla POWDER_SNOW_CAULDRON -> Brew powder snow
        if (s.is(Blocks.POWDER_SNOW_CAULDRON)) {
            int lvl = s.getValue(LayeredCauldronBlock.LEVEL);

            BlockState brew = ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get().defaultBlockState()
                    .setValue(LayeredCauldronBlock.LEVEL, lvl);

            level.setBlock(pos, brew, 3);

            ensureThermalTracked(level, pos, /*resetBrew=*/true);
            return;
        }

        // Vanilla LAVA_CAULDRON -> Brew lava
        if (s.is(Blocks.LAVA_CAULDRON)) {
            BlockState brew = ModBrewingBlocks.BREW_LAVA_CAULDRON.get().defaultBlockState();
            level.setBlock(pos, brew, 3);

            ensureThermalTracked(level, pos, /*resetBrew=*/true);
        }
    }

    /**
     * Public, stable thermal tracking initializer so the shim (and any future entry points)
     * can consistently arm the vessel without depending on private helpers in interaction map classes.
     */
    public static void ensureThermalTracked(ServerLevel level, BlockPos pos, boolean resetBrew) {
        BrewingVesselData data = BrewingVesselData.get(level);
        long key = pos.asLong();

        data.ensureTracked(key);
        BrewingVesselData.VesselState v = data.getTrackedState(key);
        if (v == null) return;

        // legacy fields inert
        v.pendingFillTicks = 0;
        v.boilProgress = 0;
        v.boilTicksRequired = 0;
        v.boiling = false;

        // Initialize temperature immediately based on current vessel block
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BrewWaterCauldronBlock) {
            v.tempC = AMBIENT_TEMP_C;
            v.lastTempC = AMBIENT_TEMP_C;
        } else if (state.getBlock() instanceof BrewPowderSnowCauldronBlock) {
            v.tempC = SNOW_BASE_TEMP_C;
            v.lastTempC = SNOW_BASE_TEMP_C;
        } else if (state.getBlock() instanceof BrewLavaCauldronBlock) {
            v.tempC = LAVA_TEMP_C;
            v.lastTempC = LAVA_TEMP_C;
        } else {
            v.tempC = Float.NaN;
            v.lastTempC = Float.NaN;
        }

        if (resetBrew) {
            v.doomed = false;
            v.matchedRecipeId = null;
            if (v.ingredients != null) v.ingredients.clear();
        }

        data.setDirty();
    }
}
