package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

public final class BrewWaterCauldronBlock extends LayeredCauldronBlock {

    /**
     * This property is the “server->client truth” that allows the client to tint water based on brew state
     * without any custom networking.
     *
     * NONE: normal water (biome tint)
     * COMPLETE: successful brew waiting to bottle
     * DOOMED: ruined brew (black/smoke later)
     */
    public static final EnumProperty<BrewState> BREW_STATE = EnumProperty.create("brew_state", BrewState.class);

    public enum BrewState implements StringRepresentable {
        NONE("none"),
        COMPLETE("complete"),
        DOOMED("doomed");

        private final String name;
        BrewState(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    public BrewWaterCauldronBlock(Properties props) {
        super(props, (precip) -> precip == Biome.Precipitation.RAIN, BrewCauldronInteractionMaps.waterMap());

        // The block may be created in-world via state swaps; ensure default is sane.
        this.registerDefaultState(
                this.stateDefinition.any()
                        .setValue(LEVEL, 1)
                        .setValue(BREW_STATE, BrewState.NONE)
        );
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
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(BREW_STATE);
    }
}
