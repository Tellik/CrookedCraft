package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import com.tellik.crookedcraft.brewing.engine.BrewStatusFormatter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrewingForgeEvents {

    private static final String MSG_RUINED = "The brew is ruined. Discard it (sneak + empty hand).";

    // -------------------------
    // Thermal constants
    // -------------------------
    private static final float WATER_BOIL_C = 100.0f;
    private static final float LAVA_BOIL_C  = 1000.0f;

    // Passive drift rates (C/tick). 20 ticks/sec.
    private static final float DRIFT_WATER_C_PER_TICK = 0.04f; // ~1.6C/sec
    private static final float DRIFT_SNOW_C_PER_TICK  = 0.03f; // slightly faster so melting feels responsive
    private static final float DRIFT_LAVA_C_PER_TICK  = 0.50f; // faster so lava->obsidian isn't "minutes"

    private static final float DIRTY_EPS = 0.0005f;

    // -------------------------------------------------------------------------
    // VesselState reflection helpers (fallback only)
    // -------------------------------------------------------------------------
    private static final String[] SOLID_FIELD_CANDIDATES = new String[] {
            "solidId",
            "solidBlockId",
            "solidItemId",
            "insertedSolidId",
            "insertedSolid",
            "solid"
    };

    private BrewingForgeEvents() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HeatSourceReloadListener());
        event.addListener(new ThermalTransformReloadListener());
    }

    @SubscribeEvent
    public static void onRightClickCauldron(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Player player = event.getEntity();

        // IMPORTANT:
        // Only protect/override behavior for OUR brew cauldrons.
        // Vanilla cauldrons must never be touched by this handler (no conversions either direction).
        if (!isBrewVesselState(state)) return;

        // MAIN_HAND only
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        ItemStack held = player.getMainHandItem();

        // 1) Empty hand: status / discard
        if (held.isEmpty()) {
            if (level.isClientSide) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            ServerLevel serverLevel = (ServerLevel) level;

            if (player.isShiftKeyDown()) {
                handleDiscard(serverLevel, pos, state, player, event);
                return;
            }

            BrewStatusFormatter.sendStatus(player, serverLevel, pos);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // 2) Sneak + item: allow normal interactions
        if (player.isShiftKeyDown()) {
            return;
        }

        // Let vanilla-like CauldronInteraction behaviors run for items the current vessel map supports
        // BUT: NEVER allow vanilla bucket-filling to run, or it will replace our brew vessel with vanilla cauldrons.
        if (!held.is(ModTags.Items.BREW_CONTAINERS)) {

            // First: intercept fluid buckets so we always stay in Brew* blocks.
            // (This must happen BEFORE we allow CauldronInteraction to run.)
            if (isFluidFillBucket(held.getItem())) {
                if (level.isClientSide) {
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }

                handleBrewFluidBucketFill((ServerLevel) level, pos, state, player, event);
                return;
            }

            // Otherwise, allow vanilla-style interactions for supported items (glass bottle, empty bucket drain, etc.)
            Map<Item, CauldronInteraction> map = getVanillaInteractionMapFor(state);
            if (map != null && map.containsKey(held.getItem())) {
                return;
            }
        }

        // Client prediction cancel if we handle it
        if (level.isClientSide) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // --- SERVER from here ---
        ServerLevel serverLevel = (ServerLevel) level;

        int cauldronLevel = getCauldronLevel(state);

        long posLong = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        data.ensureTracked(posLong);
        BrewingVesselData.VesselState v = data.getTrackedState(posLong);

        // 2.5) Solid insertion (single solid only)
        // - Only treat BlockItems as "solid inserts" when it makes sense:
        //   * Always allowed in the empty brew cauldron
        //   * Allowed in other vessels ONLY if that solid appears as required_solid in that vessel's transforms
        if (held.getItem() instanceof BlockItem bi) {
            ResourceLocation solidId = ForgeRegistries.BLOCKS.getKey(bi.getBlock());
            if (solidId == null) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            boolean isEmptyBrew = state.is(ModBrewingBlocks.BREW_CAULDRON.get());
            boolean relevantForThisVessel = ThermalTransformManager.isSolidRelevantFor(state.getBlock(), solidId);

            if (isEmptyBrew || relevantForThisVessel) {

                // Already have a solid inserted
                ResourceLocation existing = getInsertedSolidId(serverLevel, pos, v);
                if (existing != null) {
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }

                // Basic gate: non-air
                if (!ThermalTransformManager.canInsertSolid(serverLevel, pos, state, solidId)) {
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }

                // Write to BE (render source of truth)
                setInsertedSolidOnBlockEntity(serverLevel, pos, solidId);

                // Best-effort fallback to VesselState (if you later add the field)
                setInsertedSolidId(v, solidId);

                // Consume one
                held.shrink(1);

                data.setDirty();
                serverLevel.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6f, 1.2f);

                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // Not treated as a "solid insert" -> fall through to brewing/extraction logic
        }

        // Keep tint synced (water only)
        syncBrewTintIfNeeded(serverLevel, pos, state, v);

        // 3a) Bottling / extraction
        if (held.is(ModTags.Items.BREW_CONTAINERS)) {

            if (v.doomed) {
                ItemStack out = new ItemStack(ModBrewingItems.BLACK_SLUDGE.get());
                deliverResultLikeVanilla(player, InteractionHand.MAIN_HAND, held, out, serverLevel, pos);
                serverLevel.playSound(null, pos, net.minecraft.sounds.SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 0.8f);
                applyBottleDrain(serverLevel, pos, state, cauldronLevel, data, posLong);

                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            if (v.matchedRecipeId == null) {
                player.displayClientMessage(Component.literal("The brew is not ready."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            var opt = serverLevel.getRecipeManager().byKey(v.matchedRecipeId);
            CauldronBrewRecipe recipe = (opt.isPresent() && opt.get() instanceof CauldronBrewRecipe r) ? r : null;

            if (recipe == null) {
                v.doomed = true;
                v.matchedRecipeId = null;
                data.setDirty();
                syncBrewTintIfNeeded(serverLevel, pos, state, v);

                player.displayClientMessage(Component.literal("Recipe missing after reload. The brew destabilizes and fails."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            ItemStack out = recipe.createResultStack();
            if (out.isEmpty()) {
                player.displayClientMessage(Component.literal("Brew result was empty (recipe bug)."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            deliverResultLikeVanilla(player, InteractionHand.MAIN_HAND, held, out, serverLevel, pos);
            serverLevel.playSound(null, pos, net.minecraft.sounds.SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 1.0f);
            applyBottleDrain(serverLevel, pos, state, cauldronLevel, data, posLong);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Ingredients are water-only for now
        if (!(state.getBlock() instanceof BrewWaterCauldronBlock)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (cauldronLevel <= 0) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // 3b) Ingredient insertion
        if (v.doomed) {
            player.displayClientMessage(Component.literal(MSG_RUINED), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (v.matchedRecipeId != null) {
            player.displayClientMessage(Component.literal("The brew is complete. Bottle it first."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (!v.boiling) {
            player.displayClientMessage(Component.literal("The cauldron isn't boiling yet."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        Item ingredientItem = held.getItem();

        BrewingMatcher.AddResult res = BrewingMatcher.tryAddIngredient(
                serverLevel,
                BrewingMatcher.WATER_LIQUID,
                v.ingredients,
                ingredientItem
        );

        if (res.outcome == BrewingMatcher.AddOutcome.NOT_IN_ANY_RECIPE) {
            player.displayClientMessage(Component.literal("That doesn't react with this brew."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (res.outcome == BrewingMatcher.AddOutcome.AMBIGUOUS) {
            player.displayClientMessage(Component.literal("Ambiguous recipes detected. This brew cannot stabilize."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        held.shrink(1);
        recordIngredient(v, ingredientItem);

        if (res.outcome == BrewingMatcher.AddOutcome.DOOMED) {
            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();
            syncBrewTintIfNeeded(serverLevel, pos, state, v);

            serverLevel.playSound(null, pos, net.minecraft.sounds.SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.6f, 0.8f);
            player.displayClientMessage(Component.literal("The brew curdles and fails."), false);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (res.outcome == BrewingMatcher.AddOutcome.READY) {
            v.matchedRecipeId = res.matchedRecipeId;
            data.setDirty();
            syncBrewTintIfNeeded(serverLevel, pos, state, v);

            serverLevel.playSound(null, pos, net.minecraft.sounds.SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.7f, 1.2f);
            player.displayClientMessage(Component.literal("The brew is complete."), false);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        data.setDirty();
        player.displayClientMessage(Component.literal("Ingredient added. Candidates: " + res.candidatesBefore + " -> " + res.candidatesAfter), false);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    // -------------------------------------------------------------------------
    // Tick / thermal sim
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;

        BrewingVesselData data = BrewingVesselData.get(serverLevel);

        Iterator<Map.Entry<Long, BrewingVesselData.VesselState>> it = data.iterator();
        while (it.hasNext()) {
            Map.Entry<Long, BrewingVesselData.VesselState> entry = it.next();
            long posLong = entry.getKey();
            BrewingVesselData.VesselState v = entry.getValue();

            BlockPos pos = BlockPos.of(posLong);
            if (!serverLevel.isLoaded(pos)) continue;

            BlockState state = serverLevel.getBlockState(pos);

            boolean isWater = state.getBlock() instanceof BrewWaterCauldronBlock;
            boolean isLava  = state.getBlock() instanceof BrewLavaCauldronBlock;
            boolean isSnow  = state.getBlock() instanceof BrewPowderSnowCauldronBlock;
            boolean isEmptyBrew = state.is(ModBrewingBlocks.BREW_CAULDRON.get());

            // Keep tracking empty brew cauldrons IF they contain an inserted solid (so transforms can run).
            if (!isWater && !isLava && !isSnow && !isEmptyBrew) {
                it.remove();
                data.setDirty();
                continue;
            }

            if (isEmptyBrew) {
                ResourceLocation solid = getInsertedSolidId(serverLevel, pos, v);
                if (solid == null) {
                    // truly empty, no reason to track
                    it.remove();
                    data.setDirty();
                    continue;
                }
            }

            int cauldronLevel = getCauldronLevel(state);

            // Ambient temperature from biome (simple mapping).
            float ambientC = getAmbientTempC(serverLevel, pos);

            final float startTemp;
            final float driftPerTick;

            if (isWater) {
                startTemp = ambientC;
                driftPerTick = DRIFT_WATER_C_PER_TICK;
            } else if (isSnow) {
                // Powder snow starts cold, but drifts toward ambient (so it can melt in warm biomes).
                startTemp = Math.min(0.0f, ambientC);
                driftPerTick = DRIFT_SNOW_C_PER_TICK;
            } else if (isLava) {
                startTemp = LAVA_BOIL_C;
                driftPerTick = DRIFT_LAVA_C_PER_TICK;
            } else {
                // empty brew cauldron with inserted solid
                startTemp = ambientC;
                driftPerTick = DRIFT_WATER_C_PER_TICK;
            }

            if (Float.isNaN(v.tempC)) {
                v.tempC = startTemp;
                v.lastTempC = startTemp;

                v.boilProgress = 0;
                v.boilTicksRequired = 0;
                v.pendingFillTicks = 0;

                data.setDirty();
            }

            float oldTemp = v.tempC;
            boolean wasBoiling = v.boiling;

            HeatSourceManager.HeatProfile heat = HeatSourceManager.getHeatProfile(serverLevel, pos);

            float strengthBonus = getStrengthBonus(cauldronLevel);
            float speedBonus = getSpeedBonus(cauldronLevel);

            float targetTemp = (heat != null)
                    ? (heat.maxTempC() * strengthBonus)
                    : ambientC;

            // Approach target temperature
            if (heat != null) {
                float step = heat.heatPerTickC() * speedBonus;

                if (v.tempC < targetTemp) v.tempC = Math.min(v.tempC + step, targetTemp);
                else if (v.tempC > targetTemp) v.tempC = Math.max(v.tempC - step, targetTemp);
            } else {
                // Passive drift toward ambient in BOTH directions
                if (v.tempC < targetTemp) v.tempC = Math.min(v.tempC + driftPerTick, targetTemp);
                else if (v.tempC > targetTemp) v.tempC = Math.max(v.tempC - driftPerTick, targetTemp);
            }

            // Apply thermal transforms (includes reset_brew and solid clearing on block change)
            if (ThermalTransformManager.tryApplyTransforms(serverLevel, pos, state, v, it, data)) {
                continue;
            }

            // Derived boiling
            if (isWater) {
                boolean canBoil = (heat != null) && ((heat.maxTempC() * strengthBonus) >= WATER_BOIL_C);
                v.boiling = canBoil && (v.tempC >= WATER_BOIL_C - 0.001f);
            } else if (isLava) {
                v.boiling = (v.tempC >= LAVA_BOIL_C - 0.001f);
            } else {
                v.boiling = false;
            }

            if (isWater) {
                syncBrewTintIfNeeded(serverLevel, pos, state, v);
            }

            // Water-only feedback
            if (isWater && v.boiling) {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.9;
                double z = pos.getZ() + 0.5;

                serverLevel.sendParticles(ParticleTypes.BUBBLE, x, y, z, 2, 0.15, 0.05, 0.15, 0.01);

                if (!wasBoiling) {
                    serverLevel.playSound(null, pos, net.minecraft.sounds.SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.6f, 1.0f);
                    serverLevel.sendParticles(ParticleTypes.BUBBLE_POP, x, y, z, 6, 0.20, 0.05, 0.20, 0.02);
                }

                if (v.doomed) {
                    serverLevel.sendParticles(ParticleTypes.SMOKE, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                } else if (v.matchedRecipeId != null) {
                    var opt = serverLevel.getRecipeManager().byKey(v.matchedRecipeId);
                    CauldronBrewRecipe recipe = (opt.isPresent() && opt.get() instanceof CauldronBrewRecipe r) ? r : null;

                    if (recipe != null) {
                        int color = net.minecraft.world.item.alchemy.PotionUtils.getColor(recipe.createResultStack());
                        float rr = ((color >> 16) & 0xFF) / 255.0f;
                        float gg = ((color >> 8) & 0xFF) / 255.0f;
                        float bb = (color & 0xFF) / 255.0f;

                        DustParticleOptions dust = new DustParticleOptions(new Vector3f(rr, gg, bb), 0.9f);
                        serverLevel.sendParticles(dust, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                    }
                }
            }

            boolean tempChanged = Math.abs(v.tempC - oldTemp) > DIRTY_EPS;
            boolean boilChanged = (v.boiling != wasBoiling);

            if (tempChanged || boilChanged) data.setDirty();

            if (v.boilProgress != 0 || v.boilTicksRequired != 0) {
                v.boilProgress = 0;
                v.boilTicksRequired = 0;
                data.setDirty();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Discard (FIXED: clears BE render + allows discarding "solid-only" empty cauldron)
    // -------------------------------------------------------------------------
    private static void handleDiscard(ServerLevel serverLevel, BlockPos pos, BlockState state, Player player, PlayerInteractEvent.RightClickBlock event) {

        // If there is an inserted solid, ALWAYS return it and clear it first.
        ResourceLocation solidId = null;
        BlockEntity be = serverLevel.getBlockEntity(pos);
        if (be instanceof BrewVesselBlockEntity b) {
            solidId = b.getSolidBlockId();
        }

        if (solidId != null) {
            Block solidBlock = ForgeRegistries.BLOCKS.getValue(solidId);
            if (solidBlock != null) {
                ItemStack solidStack = new ItemStack(solidBlock.asItem(), 1);

                // Put in hand if empty (it is, since discard is sneak + empty hand)
                if (player.getMainHandItem().isEmpty()) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, solidStack);
                } else {
                    ItemHandlerHelper.giveItemToPlayer(player, solidStack);
                }
            }

            // Clear BE render immediately
            setInsertedSolidOnBlockEntity(serverLevel, pos, null);
        }

        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        long posLong = pos.asLong();
        BrewingVesselData.VesselState v = data.getStateIfTracked(posLong);

        // If it's truly empty (empty brew cauldron, no tracked state, and no solid), do nothing.
        boolean isEmptyBrew = state.is(ModBrewingBlocks.BREW_CAULDRON.get());
        if (isEmptyBrew && solidId == null && v == null) {
            player.displayClientMessage(Component.literal("The cauldron is already empty."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Salvage doomed sludge chance (only if we had a tracked doomed brew)
        if (v != null && v.doomed) {
            float chance = 0.10f;
            if (serverLevel.getRandom().nextFloat() <= chance) {
                ItemStack drop = new ItemStack(ModBrewingItems.DOOMED_SLUDGE.get());
                Containers.dropItemStack(serverLevel, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, drop);
                player.displayClientMessage(Component.literal("You salvage a clump of doomed sludge."), false);
            }
        }

        // Reset block to empty brew cauldron (forces correct state), untrack vessel state
        serverLevel.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
        data.untrack(posLong);

        player.displayClientMessage(Component.literal("You discard the cauldron's contents."), false);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    // -------------------------------------------------------------------------
    // Helpers / misc
    // -------------------------------------------------------------------------

    /** Strength bonus affects max achievable temperature. */
    private static float getStrengthBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.25f;
        if (cauldronLevel == 2) return 1.10f;
        return 1.0f;
    }

    /** Speed bonus affects heating rate only. */
    private static float getSpeedBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.66f;
        if (cauldronLevel == 2) return 1.33f;
        return 1.0f;
    }

    private static int getCauldronLevel(BlockState state) {
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) return state.getValue(LayeredCauldronBlock.LEVEL);
        if (state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) return state.getValue(BlockStateProperties.LEVEL_CAULDRON);
        return 3;
    }

    private static Map<Item, CauldronInteraction> getVanillaInteractionMapFor(BlockState state) {
        if (state.getBlock() instanceof BrewWaterCauldronBlock) return CauldronInteraction.WATER;
        if (state.getBlock() instanceof BrewLavaCauldronBlock) return CauldronInteraction.LAVA;
        if (state.getBlock() instanceof BrewPowderSnowCauldronBlock) return CauldronInteraction.POWDER_SNOW;
        return CauldronInteraction.EMPTY;
    }

    /**
     * Ambient temperature model.
     * Minecraft biome base temperature is roughly 0.0 (snowy) to 2.0 (desert).
     * We map it into Celsius-ish:
     *   0.0 -> about -12C
     *   0.8 -> about +12C
     *   2.0 -> about +48C
     */
    private static float getAmbientTempC(ServerLevel level, BlockPos pos) {
        float t;
        try {
            t = level.getBiome(pos).value().getBaseTemperature();
        } catch (Throwable ignored) {
            t = 0.8f;
        }

        float c = (t - 0.8f) * 30.0f + 12.0f;

        // Keep sane bounds
        if (c < -25.0f) c = -25.0f;
        if (c > 55.0f) c = 55.0f;

        return c;
    }

    // -------------------------------------------------------------------------
    // Brew-vessel only detection (prevents touching vanilla cauldrons)
    // -------------------------------------------------------------------------
    private static boolean isBrewVesselState(BlockState state) {
        if (state == null) return false;

        // Prefer explicit block identity so we never accidentally match vanilla blocks via tags.
        Block b = state.getBlock();
        return b == ModBrewingBlocks.BREW_CAULDRON.get()
                || b == ModBrewingBlocks.BREW_WATER_CAULDRON.get()
                || b == ModBrewingBlocks.BREW_LAVA_CAULDRON.get()
                || b == ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get();
    }

    // -------------------------------------------------------------------------
    // BlockEntity solid access (render source-of-truth)
    // -------------------------------------------------------------------------
    private static ResourceLocation getInsertedSolidId(ServerLevel level, BlockPos pos, BrewingVesselData.VesselState v) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BrewVesselBlockEntity b) {
            ResourceLocation rl = b.getSolidBlockId();
            if (rl != null) return rl;
        }
        return getInsertedSolidId(v);
    }

    private static void setInsertedSolidOnBlockEntity(ServerLevel level, BlockPos pos, ResourceLocation solidId) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BrewVesselBlockEntity b)) return;

        b.setSolidBlockId(solidId);
        b.setChanged();
        BlockState st = level.getBlockState(pos);
        level.sendBlockUpdated(pos, st, st, 3);
    }

    private static boolean isFluidFillBucket(Item item) {
        return item == net.minecraft.world.item.Items.WATER_BUCKET
                || item == net.minecraft.world.item.Items.LAVA_BUCKET
                || item == net.minecraft.world.item.Items.POWDER_SNOW_BUCKET;
    }

    private static void handleBrewFluidBucketFill(ServerLevel level,
                                                  BlockPos pos,
                                                  BlockState currentState,
                                                  Player player,
                                                  PlayerInteractEvent.RightClickBlock event) {

        Item heldItem = player.getMainHandItem().getItem();

        // Determine target Brew block + sound
        BlockState target;
        net.minecraft.sounds.SoundEvent sound;

        if (heldItem == net.minecraft.world.item.Items.WATER_BUCKET) {
            target = ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState();
            sound = net.minecraft.sounds.SoundEvents.BUCKET_EMPTY;
        } else if (heldItem == net.minecraft.world.item.Items.LAVA_BUCKET) {
            target = ModBrewingBlocks.BREW_LAVA_CAULDRON.get().defaultBlockState();
            sound = net.minecraft.sounds.SoundEvents.BUCKET_EMPTY_LAVA;
        } else if (heldItem == net.minecraft.world.item.Items.POWDER_SNOW_BUCKET) {
            target = ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get().defaultBlockState();
            sound = net.minecraft.sounds.SoundEvents.BUCKET_EMPTY_POWDER_SNOW;
        } else {
            // Not a fill bucket (shouldn't happen due to isFluidFillBucket gate)
            event.setCancellationResult(InteractionResult.PASS);
            return;
        }

        // If we're already the same fluid type, just top off to full and keep the vessel type (no vanilla conversion).
        boolean sameBlock = currentState.getBlock() == target.getBlock();

        // Always fill to full (3) when using a bucket.
        target = applyLevelIfPresent(target, 3);

        // If block is changing fluids/types, we should reset brew state and clear solids.
        long key = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(level);
        data.ensureTracked(key);
        BrewingVesselData.VesselState v = data.getTrackedState(key);

        // If fluid type changes, wipe brew state AND reset thermal baseline for the new fluid.
        // If same fluid type, do NOT reset temperature (top-off should preserve heat/cold).
        boolean baseFill = !sameBlock;

        if (v != null) {
            if (baseFill) {
                v.clearAll();

                // CRITICAL: reset temp baseline here, because brew bucket fills do NOT go through CauldronInteraction maps.
                if (target.getBlock() instanceof BrewWaterCauldronBlock) {
                    float ambient = getAmbientTempC(level, pos);
                    v.tempC = ambient;
                    v.lastTempC = ambient;
                } else if (target.getBlock() instanceof BrewPowderSnowCauldronBlock) {
                    // Start cold so it doesn't instantly melt due to previous fluid's temp carrying over.
                    v.tempC = -10.0f;
                    v.lastTempC = -10.0f;
                } else if (target.getBlock() instanceof BrewLavaCauldronBlock) {
                    v.tempC = LAVA_BOIL_C;
                    v.lastTempC = LAVA_BOIL_C;
                } else {
                    v.tempC = Float.NaN;
                    v.lastTempC = Float.NaN;
                }

            } else {
                // Same-fluid bucket top-off: only initialize thermals if unknown.
                if (Float.isNaN(v.tempC) || Float.isNaN(v.lastTempC)) {
                    if (target.getBlock() instanceof BrewWaterCauldronBlock) {
                        float ambient = getAmbientTempC(level, pos);
                        v.tempC = ambient;
                        v.lastTempC = ambient;
                    } else if (target.getBlock() instanceof BrewPowderSnowCauldronBlock) {
                        v.tempC = -10.0f;
                        v.lastTempC = -10.0f;
                    } else if (target.getBlock() instanceof BrewLavaCauldronBlock) {
                        v.tempC = LAVA_BOIL_C;
                        v.lastTempC = LAVA_BOIL_C;
                    }
                }
            }
        }

        // Set block (keep it in Brew* family)
        level.setBlock(pos, target, 3);

        // Clear rendered solid when fluid changes (or if you want: always clear on any bucket fill)
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BrewVesselBlockEntity b) {
            b.setSolidBlockId(null);
        }

        data.setDirty();

        // Bucket exchange (survival)
        if (!player.getAbilities().instabuild) {
            // Buckets are stack size 1, so direct replace is safe.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.BUCKET));
        }

        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0f, 1.0f);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }


    private static BlockState applyLevelIfPresent(BlockState state, int level) {
        int clamped = Math.max(1, Math.min(3, level));

        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            return state.setValue(LayeredCauldronBlock.LEVEL, clamped);
        }
        if (state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) {
            return state.setValue(BlockStateProperties.LEVEL_CAULDRON, clamped);
        }
        return state;
    }

    // -------------------------------------------------------------------------
    // VesselState reflection (fallback)
    // -------------------------------------------------------------------------
    private static ResourceLocation getInsertedSolidId(Object vesselState) {
        if (vesselState == null) return null;

        for (String name : SOLID_FIELD_CANDIDATES) {
            Object val = tryGetField(vesselState, name);
            if (val == null) continue;

            if (val instanceof ResourceLocation rl) return rl;
            if (val instanceof String s) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) return rl;
            }
        }

        return null;
    }

    private static void setInsertedSolidId(Object vesselState, ResourceLocation solidId) {
        if (vesselState == null || solidId == null) return;

        for (String name : SOLID_FIELD_CANDIDATES) {
            Field f = tryFindField(vesselState.getClass(), name);
            if (f == null) continue;

            try {
                f.setAccessible(true);

                Class<?> t = f.getType();
                if (t == ResourceLocation.class) {
                    f.set(vesselState, solidId);
                    return;
                }

                if (t == String.class) {
                    f.set(vesselState, solidId.toString());
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static Object tryGetField(Object obj, String fieldName) {
        Field f = tryFindField(obj.getClass(), fieldName);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field tryFindField(Class<?> cls, String fieldName) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Existing helpers from your file (unchanged)
    // -------------------------------------------------------------------------
    private static void deliverResultLikeVanilla(Player player,
                                                 InteractionHand hand,
                                                 ItemStack heldContainer,
                                                 ItemStack result,
                                                 ServerLevel level,
                                                 BlockPos pos) {

        if (result.isEmpty()) return;

        ItemStack out = result.copy();

        if (heldContainer.getCount() == 1) {
            if (hand == InteractionHand.MAIN_HAND) {
                int slot = player.getInventory().selected;
                player.getInventory().setItem(slot, ItemStack.EMPTY);
                ItemHandlerHelper.giveItemToPlayer(player, out, slot);
            } else {
                player.setItemInHand(hand, ItemStack.EMPTY);
                ItemHandlerHelper.giveItemToPlayer(player, out);
            }

            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return;
        }

        heldContainer.shrink(1);
        ItemHandlerHelper.giveItemToPlayer(player, out);

        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void applyBottleDrain(ServerLevel level, BlockPos pos, BlockState state, int cauldronLevel, BrewingVesselData data, long posLong) {
        int newLevel = Math.max(0, cauldronLevel - 1);

        if (newLevel <= 0) {
            level.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
            data.untrack(posLong);
        } else {
            level.setBlock(pos, state.setValue(BlockStateProperties.LEVEL_CAULDRON, newLevel), 3);
            data.setDirty();
        }
    }

    private static void syncBrewTintIfNeeded(ServerLevel level, BlockPos pos, BlockState state, BrewingVesselData.VesselState v) {
        if (!(state.getBlock() instanceof BrewWaterCauldronBlock)) return;
        if (!state.hasProperty(BrewWaterCauldronBlock.BREW_STATE)) return;

        BrewWaterCauldronBlock.BrewState desired;
        if (v == null) desired = BrewWaterCauldronBlock.BrewState.NONE;
        else if (v.doomed) desired = BrewWaterCauldronBlock.BrewState.DOOMED;
        else if (v.matchedRecipeId != null) desired = BrewWaterCauldronBlock.BrewState.COMPLETE;
        else desired = BrewWaterCauldronBlock.BrewState.NONE;

        BrewWaterCauldronBlock.BrewState current = state.getValue(BrewWaterCauldronBlock.BREW_STATE);
        if (current != desired) {
            level.setBlock(pos, state.setValue(BrewWaterCauldronBlock.BREW_STATE, desired), 2);
        }
    }

    private static void recordIngredient(BrewingVesselData.VesselState v, Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) return;
        v.ingredients.put(id, v.ingredients.getOrDefault(id, 0) + 1);
    }
}
