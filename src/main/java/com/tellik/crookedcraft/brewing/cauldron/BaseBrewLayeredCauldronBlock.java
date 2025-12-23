package com.tellik.crookedcraft.brewing.cauldron;

import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.function.Predicate;

public abstract class BaseBrewLayeredCauldronBlock extends LayeredCauldronBlock {

    protected BaseBrewLayeredCauldronBlock(Properties props,
                                           Predicate<Biome.Precipitation> precipitationFills,
                                           Map<net.minecraft.world.item.Item, CauldronInteraction> interactions) {
        super(props, precipitationFills, interactions);
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
