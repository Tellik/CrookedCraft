package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.BrewingVesselData;
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

import static com.mojang.text2speech.Narrator.LOGGER;

public final class BrewCauldronInteractionMaps {
    private BrewCauldronInteractionMaps() {}

    private static final CauldronInteraction PASS =
            (state, level, pos, player, hand, stack) -> InteractionResult.PASS;

    // Thermal defaults (match your engine assumptions)
    private static final float AMBIENT_TEMP_C = 12.0f;
    private static final float SNOW_BASE_TEMP_C = -1000.0f;
    private static final float LAVA_TEMP_C = 1000.0f;

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> emptyMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        // Start with vanilla EMPTY behaviors
        map.putAll(CauldronInteraction.EMPTY);

        // Force our custom state swaps (ONLY for our Brew empty cauldron; methods are guarded)
        map.put(Items.WATER_BUCKET, BrewCauldronInteractionMaps::fillFromWaterBucket);
        map.put(Items.POTION, BrewCauldronInteractionMaps::fillFromWaterPotion);
        map.put(Items.LAVA_BUCKET, BrewCauldronInteractionMaps::fillFromLavaBucket);
        map.put(Items.POWDER_SNOW_BUCKET, BrewCauldronInteractionMaps::fillFromPowderSnowBucket);

        return map;
    }

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> waterMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        // Vanilla water interactions (washing banners, dyeing, etc.)
        map.putAll(CauldronInteraction.WATER);

        // CRITICAL OVERRIDES:
        map.put(Items.WATER_BUCKET, BrewCauldronInteractionMaps::refillWaterBucketStayBrew);

        // Bucket-out returns to our empty brew cauldron
        map.put(Items.BUCKET, BrewCauldronInteractionMaps::takeWaterBucket);

        return map;
    }

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> lavaMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        map.putAll(CauldronInteraction.LAVA);

        // Already full; swallow (prevent vanilla swapping the block).
        map.put(Items.LAVA_BUCKET, BrewCauldronInteractionMaps::noopConsume);

        // Bucket-out returns to our empty brew cauldron
        map.put(Items.BUCKET, BrewCauldronInteractionMaps::takeLavaBucket);

        return map;
    }

    public static Object2ObjectOpenHashMap<Item, CauldronInteraction> powderSnowMap() {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> map = new Object2ObjectOpenHashMap<>();
        map.defaultReturnValue(PASS);

        map.putAll(CauldronInteraction.POWDER_SNOW);

        // CRITICAL OVERRIDE:
        map.put(Items.POWDER_SNOW_BUCKET, BrewCauldronInteractionMaps::addPowderSnowBucketStayBrew);

        // Bucket-out returns to our empty brew cauldron
        map.put(Items.BUCKET, BrewCauldronInteractionMaps::takePowderSnowBucket);

        return map;
    }

    // ---------------------------
    // Fill interactions (EMPTY -> VARIANT)
    // ---------------------------

    private static InteractionResult fillFromWaterBucket(BlockState state, Level level, BlockPos pos,
                                                         Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD:
        // If this interaction ever leaks into vanilla cauldron maps, DO NOT convert vanilla to brew.
        if (!isBrewEmptyBase(state)) return InteractionResult.PASS;

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

        startTrackingIfServer(level, pos, /*resetBrew=*/true);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult fillFromWaterPotion(BlockState state, Level level, BlockPos pos,
                                                         Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD:
        if (!isBrewEmptyBase(state)) return InteractionResult.PASS;

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

        startTrackingIfServer(level, pos, /*resetBrew=*/true);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult fillFromLavaBucket(BlockState state, Level level, BlockPos pos,
                                                        Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD:
        if (!isBrewEmptyBase(state)) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        level.setBlock(pos, ModBrewingBlocks.BREW_LAVA_CAULDRON.get().defaultBlockState(), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        // lava must be tracked for cooling->obsidian
        startTrackingIfServer(level, pos, /*resetBrew=*/true);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult fillFromPowderSnowBucket(BlockState state, Level level, BlockPos pos,
                                                              Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD:
        if (!isBrewEmptyBase(state)) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        level.setBlock(pos,
                ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get().defaultBlockState()
                        .setValue(LayeredCauldronBlock.LEVEL, 3),
                3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_POWDER_SNOW, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        // powder snow must be tracked for melting->water
        startTrackingIfServer(level, pos, /*resetBrew=*/true);
        return InteractionResult.CONSUME;
    }

    // ---------------------------
    // CRITICAL: "Stay Brew" overrides (FILLED -> FILLED)
    // ---------------------------

    /**
     * Keeps BrewWaterCauldronBlock as the block, never swaps to vanilla.
     * If brew_state isn't NONE, swallow the interaction (don't overwrite a brew).
     */
    private static InteractionResult refillWaterBucketStayBrew(BlockState state, Level level, BlockPos pos,
                                                               Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD (in case of map leakage):
        if (!state.is(ModBrewingBlocks.BREW_WATER_CAULDRON.get())) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (state.hasProperty(BrewWaterCauldronBlock.BREW_STATE)
                && state.getValue(BrewWaterCauldronBlock.BREW_STATE) != BrewWaterCauldronBlock.BrewState.NONE) {
            return InteractionResult.CONSUME;
        }

        int cur = state.getValue(LayeredCauldronBlock.LEVEL);
        if (cur >= 3) return InteractionResult.CONSUME;

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        BlockState next = state.setValue(LayeredCauldronBlock.LEVEL, 3);
        level.setBlock(pos, next, 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        // Do NOT reset brew here (this is just topping off an empty/none-state water vessel)
        startTrackingIfServer(level, pos, /*resetBrew=*/false);
        return InteractionResult.CONSUME;
    }

    /**
     * Increment powder snow level without converting to vanilla.
     */
    private static InteractionResult addPowderSnowBucketStayBrew(BlockState state, Level level, BlockPos pos,
                                                                 Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD (in case of map leakage):
        if (!state.is(ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get())) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        int cur = state.getValue(LayeredCauldronBlock.LEVEL);
        if (cur >= 3) {
            return InteractionResult.CONSUME;
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.BUCKET));
        }

        BlockState next = state.setValue(LayeredCauldronBlock.LEVEL, cur + 1);
        level.setBlock(pos, next, 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY_POWDER_SNOW, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.FILL_CAULDRON);

        // IMPORTANT: thermal system needs this tracked (do NOT stop tracking!)
        startTrackingIfServer(level, pos, /*resetBrew=*/false);
        return InteractionResult.CONSUME;
    }

    /** Swallow an interaction completely (prevents vanilla swapping the block). */
    private static InteractionResult noopConsume(BlockState state, Level level, BlockPos pos,
                                                 Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD (in case of map leakage):
        if (!state.is(ModBrewingBlocks.BREW_LAVA_CAULDRON.get())) return InteractionResult.PASS;

        return level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    // ---------------------------
    // Take interactions (VARIANT -> EMPTY)
    // ---------------------------

    private static InteractionResult takeWaterBucket(BlockState state, Level level, BlockPos pos,
                                                     Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD (in case of map leakage):
        if (!state.is(ModBrewingBlocks.BREW_WATER_CAULDRON.get())) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        int lvl = state.getValue(LayeredCauldronBlock.LEVEL);

        // Vanilla-style: only allow filling a bucket from a FULL cauldron
        if (lvl < 3) {
            return InteractionResult.PASS; // do nothing
        }

        // Set to empty brew cauldron and untrack
        level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);
        stopTrackingIfServer(level, pos);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.WATER_BUCKET));
        }

        level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.USE_CAULDRON);

        return InteractionResult.CONSUME;
    }

    private static InteractionResult takeLavaBucket(BlockState state, Level level, BlockPos pos,
                                                    Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD (in case of map leakage):
        if (!state.is(ModBrewingBlocks.BREW_LAVA_CAULDRON.get())) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        // Leaving a tracked-fluid vessel -> untrack
        stopTrackingIfServer(level, pos);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            giveOrDrop(player, new ItemStack(Items.LAVA_BUCKET));
        }

        level.playSound(null, pos, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0f, 1.0f);
        player.awardStat(Stats.USE_CAULDRON);

        return InteractionResult.CONSUME;
    }

    private static InteractionResult takePowderSnowBucket(BlockState state, Level level, BlockPos pos,
                                                          Player player, InteractionHand hand, ItemStack stack) {
        // HARD GUARD (in case of map leakage):
        if (!state.is(ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get())) return InteractionResult.PASS;

        if (level.isClientSide) return InteractionResult.SUCCESS;

        // Design rule: 1 powder snow bucket == 3 levels.
        // Therefore, only allow BUCKET extraction when the cauldron is FULL (level 3).
        int lvl = state.getValue(LayeredCauldronBlock.LEVEL);
        if (lvl < 3) {
            // Not enough volume for a full bucket; do nothing (vanilla-like behavior).
            return InteractionResult.PASS;
        }

        // Drain completely back to empty brew cauldron
        level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        // Leaving a tracked-fluid vessel -> untrack
        stopTrackingIfServer(level, pos);

        // Give powder snow bucket
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

    private static boolean isBrewEmptyBase(BlockState state) {
        // Only our empty brew cauldron is allowed to "upgrade" into brew fluid variants.
        return state.is(ModBrewingBlocks.BREW_CAULDRON.get());
    }

    private static void giveOrDrop(Player player, ItemStack toGive) {
        if (!player.getInventory().add(toGive)) player.drop(toGive, false);
    }

    /**
     * Backwards-compatible overload (defaults to reset).
     */
    private static void startTrackingIfServer(Level level, BlockPos pos) {
        startTrackingIfServer(level, pos, true);
    }

    /**
     * Start tracking the vessel if it contains one of our tracked fluids.
     *
     * Thermal init:
     * - Base fills (resetBrew=true) reset the temperature baseline for the new fluid.
     * - Top-offs (resetBrew=false) preserve existing temperature unless unknown/NaN.
     */
    private static void startTrackingIfServer(Level level, BlockPos pos, boolean resetBrew) {
        if (!(level instanceof ServerLevel sl)) return;

        BrewingVesselData data = BrewingVesselData.get(sl);
        long key = pos.asLong();

        data.ensureTracked(key);
        BrewingVesselData.VesselState v = data.getTrackedState(key);

        // legacy fields inert under thermal model
        v.pendingFillTicks = 0;
        v.boilProgress = 0;
        v.boilTicksRequired = 0;
        v.boiling = false;

        BlockState state = sl.getBlockState(pos);

        // If this interaction represents a NEW base fill, we must reset thermal baseline,
        // otherwise the previous fluid's temperature carries over and causes instant transforms.
        boolean needsThermalInit =
                resetBrew
                        || Float.isNaN(v.tempC)
                        || Float.isNaN(v.lastTempC);

        if (needsThermalInit) {
            if (state.getBlock() instanceof BrewWaterCauldronBlock) {
                // Water starts near ambient. (You can optionally derive this from biome later.)
                LOGGER.error("[crookedcraft] Water Inserted");
                v.tempC = AMBIENT_TEMP_C;
                v.lastTempC = AMBIENT_TEMP_C;
            } else if (state.getBlock() instanceof BrewPowderSnowCauldronBlock) {
                // Powder snow should start cold regardless of ambient, otherwise it melts instantly.
                LOGGER.error("[crookedcraft] Snow Inserted");
                v.tempC = SNOW_BASE_TEMP_C;
                v.lastTempC = SNOW_BASE_TEMP_C;
            } else if (state.getBlock() instanceof BrewLavaCauldronBlock) {
                LOGGER.error("[crookedcraft] Lava Inserted");
                v.tempC = LAVA_TEMP_C;
                v.lastTempC = LAVA_TEMP_C;
            } else {
                LOGGER.error("[crookedcraft] Something Inserted?");
                v.tempC = Float.NaN;
                v.lastTempC = Float.NaN;
            }
        }

        // Reset brew state only when this action represents a new “base fill”
        if (resetBrew) {
            v.doomed = false;
            v.matchedRecipeId = null;
            if (v.ingredients != null) v.ingredients.clear();
        }

        data.setDirty();
    }


    private static void stopTrackingIfServer(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl)) return;
        BrewingVesselData.get(sl).untrack(pos.asLong());
    }
}
