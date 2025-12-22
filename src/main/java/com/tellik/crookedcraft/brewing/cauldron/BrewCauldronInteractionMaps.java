package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.BrewingVesselData;
import com.tellik.crookedcraft.brewing.HeatSourceManager;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

public final class BrewCauldronInteractionMaps {
    private BrewCauldronInteractionMaps() {}

    private static final CauldronInteraction PASS = (state, level, pos, player, hand, stack) -> InteractionResult.PASS;

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> emptyMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        // Start with vanilla EMPTY behaviors
        map.putAll(CauldronInteraction.EMPTY);

        // Force our custom state swaps
        map.put(Items.WATER_BUCKET, BrewCauldronInteractionMaps::fillFromWaterBucket);
        map.put(Items.POTION, BrewCauldronInteractionMaps::fillFromWaterPotion);
        map.put(Items.LAVA_BUCKET, BrewCauldronInteractionMaps::fillFromLavaBucket);
        map.put(Items.POWDER_SNOW_BUCKET, BrewCauldronInteractionMaps::fillFromPowderSnowBucket);

        return map;
    }

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> waterMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        map.putAll(CauldronInteraction.WATER);

        // Ensure bucket-out returns to our empty brew cauldron
        map.put(Items.BUCKET, BrewCauldronInteractionMaps::takeWaterBucket);

        return map;
    }

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> lavaMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        map.putAll(CauldronInteraction.LAVA);

        // Ensure bucket-out returns to our empty brew cauldron
        map.put(Items.BUCKET, BrewCauldronInteractionMaps::takeLavaBucket);

        return map;
    }

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> powderSnowMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        map.putAll(CauldronInteraction.POWDER_SNOW);

        // Ensure bucket-out returns to our empty brew cauldron
        map.put(Items.BUCKET, BrewCauldronInteractionMaps::takePowderSnowBucket);

        return map;
    }

    // ---------------------------
    // Fill interactions (EMPTY -> VARIANT)
    // ---------------------------

    private static InteractionResult fillFromWaterBucket(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        BlockState newState = ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3)
                .setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.NONE);

        level.setBlock(pos, newState, 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        startTrackingIfServer(level, pos);

        return InteractionResult.CONSUME;
    }

    private static InteractionResult fillFromWaterPotion(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (PotionUtils.getPotion(stack) != Potions.WATER) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.GLASS_BOTTLE));
        }

        BlockState newState = ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 1)
                .setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.NONE);

        level.setBlock(pos, newState, 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        startTrackingIfServer(level, pos);

        return InteractionResult.CONSUME;
    }

    private static InteractionResult fillFromLavaBucket(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        level.setBlock(pos, ModBrewingBlocks.BREW_LAVA_CAULDRON.get().defaultBlockState(), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        stopTrackingIfServer(level, pos);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult fillFromPowderSnowBucket(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        level.setBlock(pos, ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get().defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_POWDER_SNOW, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        stopTrackingIfServer(level, pos);
        return InteractionResult.CONSUME;
    }

    // ---------------------------
    // Take interactions (VARIANT -> EMPTY)
    // ---------------------------

    private static InteractionResult takeWaterBucket(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        int lvl = state.getValue(LayeredCauldronBlock.LEVEL);
        if (lvl > 1) {
            level.setBlock(pos, state.setValue(LayeredCauldronBlock.LEVEL, lvl - 1), 3);
        } else {
            level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
            stopTrackingIfServer(level, pos);
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.WATER_BUCKET));
        }

        level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.USE_CAULDRON);

        return InteractionResult.CONSUME;
    }

    private static InteractionResult takeLavaBucket(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.LAVA_BUCKET));
        }

        level.playSound(null, pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.USE_CAULDRON);

        return InteractionResult.CONSUME;
    }

    private static InteractionResult takePowderSnowBucket(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        int lvl = state.getValue(LayeredCauldronBlock.LEVEL);
        if (lvl > 1) {
            level.setBlock(pos, state.setValue(LayeredCauldronBlock.LEVEL, lvl - 1), 3);
        } else {
            level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.POWDER_SNOW_BUCKET));
        }

        level.playSound(null, pos, SoundEvents.BUCKET_FILL_POWDER_SNOW, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.USE_CAULDRON);

        return InteractionResult.CONSUME;
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    private static void giveOrDrop(Player player, ItemStack toGive) {
        if (!player.getInventory().add(toGive)) player.drop(toGive, false);
    }

    private static void startTrackingIfServer(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return;

        BrewingVesselData data = BrewingVesselData.get(sl);
        long key = pos.asLong();

        data.ensureTracked(key);
        BrewingVesselData.VesselState v = data.getTrackedState(key);

        v.pendingFillTicks = 0;
        v.boilProgress = 0;
        v.boiling = false;

        Integer req = HeatSourceManager.getBoilTicksFor(sl, pos);
        v.boilTicksRequired = (req != null) ? req : 0;

        // new water fill should clear prior brew state
        v.doomed = false;
        v.matchedRecipeId = null;
        v.ingredients.clear();

        data.setDirty();
    }

    private static void stopTrackingIfServer(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return;
        BrewingVesselData.get(sl).untrack(pos.asLong());
    }
}
