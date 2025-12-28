package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.ModBrewingBlockEntities;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BrewPowderSnowCauldronBlock extends BaseBrewLayeredCauldronBlock implements EntityBlock {
    public BrewPowderSnowCauldronBlock(Properties props) {
        super(props, (precip) -> precip == Biome.Precipitation.SNOW, BrewCauldronInteractionMaps.powderSnowMap());
    }

    @Override
    public boolean isFull(BlockState state) {
        return state.getValue(LEVEL) == 3;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBrewingBlocks.BREW_CAULDRON_ITEM.get());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBrewingBlockEntities.BREW_VESSEL.get().create(pos, state);
    }

}
