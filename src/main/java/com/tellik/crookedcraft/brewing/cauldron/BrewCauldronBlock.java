package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.BrewingVesselData;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BrewCauldronBlock extends BaseBrewAbstractCauldronBlock {
    private static final float RAIN_FILL_CHANCE = 0.05F;
    private static final float POWDER_SNOW_FILL_CHANCE = 0.10F;

    public BrewCauldronBlock(Properties props) {
        super(props, BrewCauldronInteractionMaps.emptyMap());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BrewCauldronVoxelShapes.TUB;
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        // Keeps ray-trace interaction aligned with the outline + collision.
        // If you later want “more click-through”, we can slim this down further.
        return BrewCauldronVoxelShapes.TUB;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // This is the outline wireframe you see when looking at the block.
        // Returning the same tub shape prevents a mismatched outline box.
        return BrewCauldronVoxelShapes.TUB;
    }

    // ------------------------------------------------------------------------
    // Vanilla-like cauldron overrides
    // ------------------------------------------------------------------------

    @Override
    public boolean isFull(BlockState state) {
        return false;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBrewingBlocks.BREW_CAULDRON_ITEM.get());
    }

    @Override
    public void handlePrecipitation(BlockState state, Level level, BlockPos pos, Biome.Precipitation precipitation) {
        if (!level.isRainingAt(pos.above())) return;

        if (precipitation == Biome.Precipitation.RAIN) {
            if (level.getRandom().nextFloat() < RAIN_FILL_CHANCE) {
                level.setBlockAndUpdate(
                        pos,
                        ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState()
                                .setValue(LayeredCauldronBlock.LEVEL, 1)
                );
                level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

                // CRITICAL: rain-created water cauldrons must be tracked or they will never boil until interacted with.
                if (level instanceof ServerLevel serverLevel) {
                    BrewingVesselData data = BrewingVesselData.get(serverLevel);
                    data.ensureTracked(pos.asLong());
                    data.setDirty();
                }
            }
        } else if (precipitation == Biome.Precipitation.SNOW) {
            if (level.getRandom().nextFloat() < POWDER_SNOW_FILL_CHANCE) {
                level.setBlockAndUpdate(
                        pos,
                        ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get().defaultBlockState()
                                .setValue(LayeredCauldronBlock.LEVEL, 1)
                );
                level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);
            }
        }
    }

    @Override
    protected void receiveStalactiteDrip(BlockState state, Level level, BlockPos pos, Fluid fluid) {
        if (fluid == Fluids.WATER) {
            level.setBlockAndUpdate(
                    pos,
                    ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState()
                            .setValue(LayeredCauldronBlock.LEVEL, 3)
            );
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

            // If you ever re-enable drip-filling, this keeps boiling correct too.
            if (level instanceof ServerLevel serverLevel) {
                BrewingVesselData data = BrewingVesselData.get(serverLevel);
                data.ensureTracked(pos.asLong());
                data.setDirty();
            }
            return;
        }

        if (fluid == Fluids.LAVA) {
            level.setBlockAndUpdate(pos, ModBrewingBlocks.BREW_LAVA_CAULDRON.get().defaultBlockState());
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);
        }
    }
}
