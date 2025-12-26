package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import com.tellik.crookedcraft.brewing.engine.BrewStatusFormatter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrewingForgeEvents {

    private static final String MSG_RUINED = "The brew is ruined. Discard it (sneak + empty hand).";

    // -------------------------
    // Thermal constants (tune later; can be datapack-driven later)
    // -------------------------
    private static final float AMBIENT_TEMP_C = 12.0f;

    private static final float WATER_BOIL_C = 100.0f;

    private static final float SNOW_BASE_C = -10.0f;
    private static final float SNOW_MELT_C = 0.0f;

    private static final float LAVA_BOIL_C = 1000.0f;
    private static final float LAVA_SOLIDIFY_C = 800.0f;

    // Cooling speeds (C/tick). These were too fast in your previous version.
    // ~20 ticks/sec. So 0.05C/tick = 1C/sec.
    private static final float COOL_WATER_C_PER_TICK = 0.08f; // ~1.6C/sec
    private static final float COOL_SNOW_C_PER_TICK  = 0.05f; // ~1.0C/sec
    private static final float COOL_LAVA_C_PER_TICK  = 0.25f; // ~5.0C/sec (so obsidian happens in sane time)

    private static final float MIN_HEAT_STEP_C_PER_TICK = 0.001f;
    private static final float DIRTY_EPS = 0.0005f;

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

        if (!state.is(ModTags.Blocks.BREW_VESSELS)) return;

        // MAIN_HAND only; also hard-cancel offhand to prevent swing/ghost paths.
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            return;
        }

        ItemStack held = player.getMainHandItem();

        // =========================
        // 1) EMPTY HAND: status/discard always
        // =========================
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

        // =========================
        // 2) NON-EMPTY HAND + SNEAK: allow normal cauldron interactions
        // =========================
        if (player.isShiftKeyDown()) {
            return;
        }

        // Let normal CauldronInteraction behaviors run for vanilla cauldron items
        // (buckets, bottles, washing banners/armor, dyes, etc.)
        // BUT do NOT let it steal your custom brew container extraction.
        if (!held.is(ModTags.Items.BREW_CONTAINERS) && CauldronInteraction.WATER.containsKey(held.getItem())) {
            return; // do not cancel; allow block's use() to route through interaction map
        }


        // If we're going to handle this, cancel client-side to stop predicted placement flicker.
        if (level.isClientSide) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // --- SERVER from here ---
        ServerLevel serverLevel = (ServerLevel) level;

        int cauldronLevel = getCauldronLevel(state);
        if (cauldronLevel <= 0) return;

        long posLong = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        data.ensureTracked(posLong);
        BrewingVesselData.VesselState v = data.getTrackedState(posLong);

        // IMPORTANT: In the new thermal system, boilTicksRequired is legacy.
        // Do not try to infer/overwrite it here. Boiling comes from v.tempC vs boil point.

        // Keep tint synced
        syncBrewTintIfNeeded(serverLevel, pos, state, v);

        // -------------------------
        // 3a) Bottling / extraction
        // -------------------------
        if (held.is(ModTags.Items.BREW_CONTAINERS)) {

            // DOOMED => Black Sludge
            if (v.doomed) {
                ItemStack out = new ItemStack(ModBrewingItems.BLACK_SLUDGE.get());

                deliverResultLikeVanilla(player, InteractionHand.MAIN_HAND, held, out, serverLevel, pos);

                serverLevel.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 0.8f);

                applyBottleDrain(serverLevel, pos, state, cauldronLevel, data, posLong);

                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // READY => recipe result
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

            serverLevel.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 1.0f);

            applyBottleDrain(serverLevel, pos, state, cauldronLevel, data, posLong);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // -------------------------
        // 3b) Ingredient insertion
        // -------------------------
        if (v.doomed) {
            player.displayClientMessage(Component.literal(MSG_RUINED), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Lock READY: cannot add after completion
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

            serverLevel.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.6f, 0.8f);
            player.displayClientMessage(Component.literal("The brew curdles and fails."), false);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (res.outcome == BrewingMatcher.AddOutcome.READY) {
            v.matchedRecipeId = res.matchedRecipeId;
            data.setDirty();
            syncBrewTintIfNeeded(serverLevel, pos, state, v);

            serverLevel.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.7f, 1.2f);
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

    /**
     * Vanilla-style item delivery:
     * - If the held container stack is EXACTLY 1: replace the held item with the result (no inventory slot needed).
     * - If the held container stack > 1: consume 1, then try to add the result; if it cannot fit, force-drop it.
     */
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

    private static void handleDiscard(ServerLevel serverLevel, BlockPos pos, BlockState state, Player player, PlayerInteractEvent.RightClickBlock event) {
        if (state.is(ModBrewingBlocks.BREW_CAULDRON.get())) {
            player.displayClientMessage(Component.literal("The cauldron is already empty."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        BrewingVesselData.VesselState v = data.getStateIfTracked(pos.asLong());

        if (v != null && v.doomed) {
            float chance = 0.10f;
            if (serverLevel.getRandom().nextFloat() <= chance) {
                ItemStack drop = new ItemStack(ModBrewingItems.DOOMED_SLUDGE.get());
                Containers.dropItemStack(serverLevel, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, drop);
                player.displayClientMessage(Component.literal("You salvage a clump of doomed sludge."), false);
            }
        }

        serverLevel.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
        data.untrack(pos.asLong());

        player.displayClientMessage(Component.literal("You discard the cauldron's contents."), false);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
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

    // -------------------------
    // Tick / thermal sim
    // -------------------------
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

            // If it isn't one of our tracked fluid vessels anymore, untrack.
            if (!isWater && !isLava && !isSnow) {
                it.remove();
                data.setDirty();
                continue;
            }

            int cauldronLevel = getCauldronLevel(state);

            // Per-fluid model
            final float boilTemp;
            final float equilibriumTemp;
            final float startTemp;
            final float coolPerTick;

            if (isWater) {
                boilTemp = WATER_BOIL_C;
                equilibriumTemp = AMBIENT_TEMP_C;
                startTemp = AMBIENT_TEMP_C;
                coolPerTick = COOL_WATER_C_PER_TICK;
            } else if (isSnow) {
                boilTemp = WATER_BOIL_C;
                equilibriumTemp = SNOW_BASE_C;
                startTemp = SNOW_BASE_C;
                coolPerTick = COOL_SNOW_C_PER_TICK;
            } else {
                boilTemp = LAVA_BOIL_C;
                equilibriumTemp = AMBIENT_TEMP_C;
                startTemp = LAVA_BOIL_C;
                coolPerTick = COOL_LAVA_C_PER_TICK;
            }

            // Init temps if needed.
            if (Float.isNaN(v.tempC)) {
                v.tempC = startTemp;
                v.lastTempC = startTemp;

                // Keep legacy fields inert under the new system
                v.boilProgress = 0;
                v.boilTicksRequired = 0;
                v.pendingFillTicks = 0;

                data.setDirty();
            }

            float oldTemp = v.tempC;
            boolean wasBoiling = v.boiling;

            // Heat profile (datapack-driven)
            HeatSourceManager.HeatProfile heat = HeatSourceManager.getHeatProfile(serverLevel, pos);

            // Strength: affects max achievable temp (your “small amount can boil with weak heat” mechanic)
            float strengthBonus = getStrengthBonus(cauldronLevel);

            // Speed: affects heating rate only (requested: L1 +66%, L2 +33%)
            float speedBonus = getSpeedBonus(cauldronLevel);

            float targetTemp;
            float heatPerTick;

            if (heat != null) {
                targetTemp = heat.maxTempC() * strengthBonus;
                heatPerTick = heat.heatPerTickC();
            } else {
                targetTemp = equilibriumTemp;
                heatPerTick = 0.0f;
            }

            // Approach target temperature
            if (heat != null) {
                // Active source: approach the source target using its rate (speed bonus applies)
                float step = heat.heatPerTickC() * speedBonus;

                if (v.tempC < targetTemp) {
                    v.tempC = Math.min(v.tempC + step, targetTemp);
                } else if (v.tempC > targetTemp) {
                    v.tempC = Math.max(v.tempC - step, targetTemp);
                }
            } else {
                // No source: passive drift toward equilibrium using fluid-specific cooling rate
                if (v.tempC < targetTemp) {
                    // You could optionally allow passive warming here, but typically "no heat" shouldn't warm faster than 0.
                    // If you DO want passive warming back to ambient, keep a small drift step; otherwise do nothing.
                    v.tempC = Math.min(v.tempC + 0.0f, targetTemp);
                } else if (v.tempC > targetTemp) {
                    v.tempC = Math.max(v.tempC - coolPerTick, targetTemp);
                }
            }


            // Powder snow melt -> water conversion
            if (isSnow && v.tempC >= SNOW_MELT_C) {
                int newWaterLevel = 1; // for now

                BlockState newState = ModBrewingBlocks.BREW_WATER_CAULDRON.get().defaultBlockState()
                        .setValue(LayeredCauldronBlock.LEVEL, newWaterLevel)
                        .setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.NONE);

                serverLevel.setBlock(pos, newState, 3);
                serverLevel.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

                v.doomed = false;
                v.matchedRecipeId = null;
                v.ingredients.clear();

                v.tempC = 0.1f;
                v.lastTempC = 0.0f;

                data.setDirty();
                continue;
            }

            // Thermal transformations (datapack-driven)
            // Applies to ANY brew vessel type (water/lava/snow) if a transform exists for the current block.
            {
                boolean applied = ThermalTransformManager.tryApplyCoolingTransform(serverLevel, pos, state, v, it, data);
                if (applied) {
                    // transform manager already handled state/dirty/untrack/continue
                    continue;
                }
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

            // Sync tint (water only)
            if (isWater) {
                syncBrewTintIfNeeded(serverLevel, pos, state, v);
            }

            // Particles/feedback (water only)
            if (isWater && v.boiling) {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.9;
                double z = pos.getZ() + 0.5;

                serverLevel.sendParticles(ParticleTypes.BUBBLE, x, y, z, 2, 0.15, 0.05, 0.15, 0.01);

                if (!wasBoiling) {
                    serverLevel.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.6f, 1.0f);
                    serverLevel.sendParticles(ParticleTypes.BUBBLE_POP, x, y, z, 6, 0.20, 0.05, 0.20, 0.02);
                }

                if (v.doomed) {
                    serverLevel.sendParticles(ParticleTypes.SMOKE, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                    if ((serverLevel.getGameTime() & 3L) == 0L) {
                        serverLevel.sendParticles(ParticleTypes.ASH, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                    }
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

            if (tempChanged || boilChanged) {
                data.setDirty();
            }

            // Keep legacy boil counters inert
            if (v.boilProgress != 0 || v.boilTicksRequired != 0) {
                v.boilProgress = 0;
                v.boilTicksRequired = 0;
                data.setDirty();
            }
        }
    }

    /** Strength bonus affects max achievable temperature. */
    private static float getStrengthBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.25f;
        if (cauldronLevel == 2) return 1.10f;
        return 1.0f;
    }

    /** Speed bonus affects heating rate only (requested). */
    private static float getSpeedBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.66f; // +66%
        if (cauldronLevel == 2) return 1.33f; // +33%
        return 1.0f;
    }

    // -------------------------
    // Small helpers
    // -------------------------
    private static int getCauldronLevel(BlockState state) {
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        if (state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) {
            return state.getValue(BlockStateProperties.LEVEL_CAULDRON);
        }
        return 3;
    }
}
