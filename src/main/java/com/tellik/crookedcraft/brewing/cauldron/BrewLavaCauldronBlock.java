package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class BrewLavaCauldronBlock extends AbstractCauldronBlock {
    public BrewLavaCauldronBlock(Properties props) {
        super(props, BrewCauldronInteractionMaps.lavaMap());
    }

    @Override
    public boolean isFull(BlockState state) {
        return true;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBrewingBlocks.BREW_CAULDRON_ITEM.get());
    }
}
