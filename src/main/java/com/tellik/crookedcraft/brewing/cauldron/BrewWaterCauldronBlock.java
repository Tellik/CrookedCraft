package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.BrewingMatcher;
import com.tellik.crookedcraft.brewing.BrewingVesselData;
import com.tellik.crookedcraft.brewing.ModBrewingBlockEntities;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraftforge.registries.ForgeRegistries;

public final class BrewWaterCauldronBlock extends BaseBrewLayeredCauldronBlock implements EntityBlock {

    public static final EnumProperty<BrewState> BREW_STATE = EnumProperty.create("brew_state", BrewState.class);

    public enum BrewState implements StringRepresentable {
        NONE("none"),
        COMPLETE("complete"),
        DOOMED("doomed");

        private final String name;
        BrewState(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    // Rate-limit tag for tossed-item processing (prevents eating whole stacks instantly while resting in the fluid)
    private static final String TAG_LAST_INGEST_TICK = "crookedcraft_brew_ing_tick";

    public BrewWaterCauldronBlock(Properties props) {
        super(props, (precip) -> precip == Biome.Precipitation.RAIN, BrewCauldronInteractionMaps.waterMap());

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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBrewingBlockEntities.BREW_VESSEL.get().create(pos, state);
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

    /**
     * Thrown-item ingredient ingestion.
     *
     * Design rule (matches your right-click logic):
     * - Only ingest ingredients when this is water, it's boiling, not doomed, and not already complete.
     * - Everything else should be ignored (no "mystery consumption").
     *
     * NOTE:
     * Solid-block insertion-by-throw belongs on the EMPTY vessel block (brew_cauldron),
     * not on the water variant. This class only handles ingredient ingestion for boiling water.
     */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        super.entityInside(state, level, pos, entity);

        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        if (!(entity instanceof ItemEntity itemEntity)) return;
        if (!itemEntity.isAlive()) return;

        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;

        // Track vessel state
        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        long key = pos.asLong();
        data.ensureTracked(key);
        BrewingVesselData.VesselState v = data.getTrackedState(key);
        if (v == null) return;

        // Rate-limit: 1 attempt every 5 ticks per item entity.
        long now = serverLevel.getGameTime();
        long last = itemEntity.getPersistentData().getLong(TAG_LAST_INGEST_TICK);
        if (last != 0 && (now - last) < 5) return;
        itemEntity.getPersistentData().putLong(TAG_LAST_INGEST_TICK, now);

        // Only while actively brewing (matches right-click gating)
        int lvl = state.getValue(LayeredCauldronBlock.LEVEL);

        boolean isActivelyBrewing =
                (lvl > 0) &&
                        (!v.doomed) &&
                        (v.matchedRecipeId == null) &&
                        (v.boiling);

        if (!isActivelyBrewing) return;

        Item item = stack.getItem();

        BrewingMatcher.AddResult res = BrewingMatcher.tryAddIngredient(
                serverLevel,
                BrewingMatcher.WATER_LIQUID,
                v.ingredients,
                item
        );

        // Doesn't react -> ignore entirely
        if (res.outcome == BrewingMatcher.AddOutcome.NOT_IN_ANY_RECIPE) return;

        // Ambiguous -> doom it (same behavior you already use on right-click)
        if (res.outcome == BrewingMatcher.AddOutcome.AMBIGUOUS) {
            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();
            return;
        }

        // Consume exactly 1 item
        stack.shrink(1);
        itemEntity.setItem(stack);

        // Record ingredient (same as your BrewingForgeEvents.recordIngredient logic)
        var id = ForgeRegistries.ITEMS.getKey(item);
        if (id != null) {
            v.ingredients.put(id, v.ingredients.getOrDefault(id, 0) + 1);
        }

        if (res.outcome == BrewingMatcher.AddOutcome.DOOMED) {
            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();
        } else if (res.outcome == BrewingMatcher.AddOutcome.READY) {
            v.matchedRecipeId = res.matchedRecipeId;
            data.setDirty();
        } else {
            // NORMAL: candidates narrowed, not complete yet
            data.setDirty();
        }

        // If we ate the last item, remove the entity
        if (stack.isEmpty()) {
            itemEntity.discard();
        }
    }

    @Override
    public void handlePrecipitation(BlockState state, Level level, BlockPos pos, Biome.Precipitation precipitation) {
        int before = state.getValue(LEVEL);

        super.handlePrecipitation(state, level, pos, precipitation);

        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockState after = level.getBlockState(pos);
        if (!(after.getBlock() instanceof BrewWaterCauldronBlock)) return;

        int afterLevel = after.getValue(LEVEL);
        if (afterLevel != before && afterLevel > 0) {
            // CRITICAL: rain-incremented water levels must still be tracked so boil starts without needing an ingredient attempt.
            BrewingVesselData data = BrewingVesselData.get(serverLevel);
            data.ensureTracked(pos.asLong());
            data.setDirty();
        }
    }
}