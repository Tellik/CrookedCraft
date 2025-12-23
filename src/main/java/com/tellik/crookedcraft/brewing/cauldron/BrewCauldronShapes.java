package com.tellik.crookedcraft.brewing.cauldron;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Shared outline/collision/interaction shape for all Brew Cauldron variants.
 *
 * Uses Block.box() in "pixel" units (0..16).
 * Doubles are allowed (e.g. 4.5) because VoxelShape stores doubles internally.
 */
public final class BrewCauldronShapes {
    private BrewCauldronShapes() {}

    // Walls: thickness 1, inset by 2
    // Y range chosen based on your final accepted setup.
    private static final VoxelShape WALL_NORTH = Block.box(2, 4.5, 2, 14, 14.75, 3);
    private static final VoxelShape WALL_SOUTH = Block.box(2, 4.5, 13, 14, 14.75, 14);
    private static final VoxelShape WALL_WEST  = Block.box(2, 4.5, 3, 3, 14.75, 13);
    private static final VoxelShape WALL_EAST  = Block.box(13, 4.5, 3, 14, 14.75, 13);

    // Floor (your final accepted floor)
    private static final VoxelShape INSIDE_FLOOR = Block.box(3, 4.5, 3, 13, 5, 13);

    public static final VoxelShape TUB = Shapes.or(
            WALL_NORTH, WALL_SOUTH, WALL_WEST, WALL_EAST, INSIDE_FLOOR
    );
}
