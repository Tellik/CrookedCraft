package com.tellik.crookedcraft.brewing.cauldron;

import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

public abstract class BaseBrewAbstractCauldronBlock extends AbstractCauldronBlock {

    protected BaseBrewAbstractCauldronBlock(Properties props,
                                            Map<net.minecraft.world.item.Item, CauldronInteraction> interactions) {
        super(props, interactions);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return BrewCauldronShapes.TUB;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return BrewCauldronShapes.TUB;
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return BrewCauldronShapes.TUB;
    }
}
