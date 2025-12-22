package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrewingForgeEvents {

    private static final String MSG_RUINED_DISCARD = "The brew is ruined. Discard it (sneak + empty hand).";

    // "Small chance" – tune later or move to config when you want.
    private static final float DOOMED_SLUDGE_DROP_CHANCE = 0.12f;

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HeatSourceReloadListener());
    }

    @SubscribeEvent
    public static void onRightClickCauldron(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        // MAIN_HAND only to avoid duplicate messages / double-processing.
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        BlockPos pos = event.getPos();
        BlockState state = serverLevel.getBlockState(pos);
        Player player = event.getEntity();

        // Only care about our brew cauldrons (any variant: empty/water/lava/powder snow).
        if (!state.is(ModTags.Blocks.BREW_VESSELS)) return;

        ItemStack held = player.getMainHandItem();

        // =========================
        // 1) EMPTY HAND: status/discard (always ours)
        // =========================
        if (held.isEmpty()) {
            if (player.isShiftKeyDown()) {
                // If it's already the empty brew cauldron, do NOT "discard".
                if (state.is(ModBrewingBlocks.BREW_CAULDRON.get())) {
                    player.displayClientMessage(Component.literal("The cauldron is already empty."), false);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    event.setCanceled(true);
                    return;
                }

                // If it's a brew-water cauldron, we may need to roll doomed discard reward.
                boolean gaveDoomedSludge = false;

                BrewingVesselData data = BrewingVesselData.get(serverLevel);
                long key = pos.asLong();
                BrewingVesselData.VesselState v = data.getStateIfTracked(key);

                if (v != null && v.doomed) {
                    // roll chance
                    if (serverLevel.getRandom().nextFloat() < DOOMED_SLUDGE_DROP_CHANCE) {
                        giveOrDrop(player, new ItemStack(ModBrewingItems.DOOMED_SLUDGE.get()));
                        gaveDoomedSludge = true;
                    }
                }

                // Reset to empty brew cauldron
                serverLevel.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);

                // Clear tracking if present
                data.untrack(key);

                player.displayClientMessage(Component.literal("You discard the cauldron's contents."), false);
                if (gaveDoomedSludge) {
                    player.displayClientMessage(Component.literal("You salvage a bit of doomed sludge."), false);
                }

                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // Status message: show even if empty / lava / snow.
            sendGenericStatus(player, serverLevel, pos, state);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // =========================
        // 2) NON-EMPTY HAND + SNEAK: do nothing special
        //    This preserves "normal cauldron interactions" and prevents accidental brewing insertion.
        // =========================
        if (player.isShiftKeyDown()) {
            return; // DO NOT cancel; allow cauldron interaction maps to run normally.
        }

        // =========================
        // 3) NON-EMPTY HAND (not sneaking):
        //    We ONLY intercept if this is brew water and the held item is a brew container or a valid brew ingredient/catalyst.
        //    Otherwise: do nothing (vanilla/cauldron maps handle buckets etc.)
        // =========================

        // Only water cauldron participates in brewing logic right now.
        if (!(state.getBlock() instanceof BrewWaterCauldronBlock)) {
            return; // DO NOT cancel.
        }

        int level = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                : 0;

        // If it's water but level is 0 (shouldn't happen), just let normal interactions handle it.
        if (level <= 0) return;

        long posLong = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        data.ensureTracked(posLong);
        BrewingVesselData.VesselState v = data.getTrackedState(posLong);

        HeatSourceManager.HeatInfo heat = HeatSourceManager.getHeatInfo(serverLevel, pos);
        if (heat != null && v.boilTicksRequired <= 0) {
            v.boilTicksRequired = heat.boilTicksRequired;
            data.setDirty();
        }

        // -------------------------
        // 3a) Bottle/container extraction
        // -------------------------
        if (held.is(ModTags.Items.BREW_CONTAINERS)) {

            // DOOMED => Black Sludge (custom output)
            if (v.doomed) {
                held.shrink(1);

                ItemStack out = new ItemStack(ModBrewingItems.BLACK_SLUDGE.get());
                giveOrDrop(player, out);

                serverLevel.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 0.9f);

                // Reduce cauldron level, preserve DOOMED tint
                int newLevel = Math.max(0, level - 1);
                if (newLevel <= 0) {
                    serverLevel.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
                    data.untrack(posLong);
                } else {
                    BlockState newState = state.setValue(BlockStateProperties.LEVEL_CAULDRON, newLevel)
                            .setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.DOOMED);
                    serverLevel.setBlock(pos, newState, 3);
                    data.setDirty();
                }

                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // Not doomed: must be ready
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

                // flip tint immediately
                serverLevel.setBlock(pos, state.setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.DOOMED), 3);

                player.displayClientMessage(Component.literal("Recipe missing after reload. The brew destabilizes and fails."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            // Consume one container
            held.shrink(1);

            ItemStack out = recipe.createResultStack();
            giveOrDrop(player, out);

            serverLevel.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 1.0f);

            // Reduce cauldron level, preserve COMPLETE tint
            int newLevel = Math.max(0, level - 1);
            if (newLevel <= 0) {
                serverLevel.setBlock(pos, ModBrewingBlocks.BREW_CAULDRON.get().defaultBlockState(), 3);
                data.untrack(posLong);
            } else {
                BlockState newState = state.setValue(BlockStateProperties.LEVEL_CAULDRON, newLevel)
                        .setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.COMPLETE);
                serverLevel.setBlock(pos, newState, 3);
                data.setDirty();
            }

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // -------------------------
        // 3b) Ingredient/catalyst insertion (right-click)
        // -------------------------
        if (v.doomed) {
            player.displayClientMessage(Component.literal(MSG_RUINED_DISCARD), false);
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

        // If already READY and they add anything, ruin it (consumes 1)
        if (v.matchedRecipeId != null) {
            held.shrink(1);
            recordIngredient(v, ingredientItem);

            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();

            // set doomed tint
            serverLevel.setBlock(pos, state.setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.DOOMED), 3);

            serverLevel.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.6f, 0.8f);
            player.displayClientMessage(Component.literal("You ruined the finished brew."), false);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

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

        // Consume 1 ingredient and record it
        held.shrink(1);
        recordIngredient(v, ingredientItem);

        if (res.outcome == BrewingMatcher.AddOutcome.DOOMED) {
            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();

            serverLevel.setBlock(pos, state.setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.DOOMED), 3);

            serverLevel.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.6f, 0.8f);
            player.displayClientMessage(Component.literal("The brew curdles and fails."), false);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (res.outcome == BrewingMatcher.AddOutcome.READY) {
            v.matchedRecipeId = res.matchedRecipeId;
            data.setDirty();

            serverLevel.setBlock(pos, state.setValue(BrewWaterCauldronBlock.BREW_STATE, BrewWaterCauldronBlock.BrewState.COMPLETE), 3);

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

    private static void recordIngredient(BrewingVesselData.VesselState v, Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) return;
        v.ingredients.put(id, v.ingredients.getOrDefault(id, 0) + 1);
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack.copy())) {
            player.drop(stack.copy(), false);
        }
    }

    /**
     * Status output that works for ALL brew cauldron variants (empty/water/lava/snow).
     */
    private static void sendGenericStatus(Player player, ServerLevel level, BlockPos pos, BlockState state) {
        player.displayClientMessage(Component.literal("=== CrookedCraft Brewing ==="), false);

        if (state.getBlock() instanceof BrewWaterCauldronBlock) {
            int waterLevel = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                    ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                    : 0;

            player.displayClientMessage(Component.literal("Vessel: Brew Cauldron (Water " + waterLevel + "/3)"), false);

            HeatSourceManager.HeatInfo heat = HeatSourceManager.getHeatInfo(level, pos);
            if (heat == null) {
                player.displayClientMessage(Component.literal("Heat: None detected below the cauldron."), false);
            } else {
                player.displayClientMessage(Component.literal("Heat: " + heat.asDisplayString()), false);
            }

            BrewingVesselData data = BrewingVesselData.get(level);
            BrewingVesselData.VesselState v = data.getStateIfTracked(pos.asLong());

            if (v == null) {
                player.displayClientMessage(Component.literal("Phase: IDLE"), false);
                player.displayClientMessage(Component.literal("Ingredients: (none)"), false);
                return;
            }

            String phase;
            if (v.doomed) phase = "DOOMED";
            else if (v.matchedRecipeId != null) phase = "READY";
            else if (v.boiling) phase = "BREWING";
            else phase = "HEATING";

            player.displayClientMessage(Component.literal("Phase: " + phase), false);

            if (v.ingredients.isEmpty()) {
                player.displayClientMessage(Component.literal("Ingredients: (none)"), false);
            } else {
                player.displayClientMessage(Component.literal("Ingredients:"), false);
                for (Map.Entry<ResourceLocation, Integer> e : v.ingredients.entrySet()) {
                    player.displayClientMessage(Component.literal(" - " + e.getKey() + " x" + e.getValue()), false);
                }
            }

            if (v.matchedRecipeId != null) {
                player.displayClientMessage(Component.literal("Matched: " + v.matchedRecipeId), false);
                player.displayClientMessage(Component.literal("Use a brew container to collect."), false);
            } else if (v.doomed) {
                player.displayClientMessage(Component.literal("Discard: sneak + empty hand."), false);
            } else {
                player.displayClientMessage(Component.literal("Tip: Sneak + empty hand to discard."), false);
            }
        } else {
            player.displayClientMessage(Component.literal("Vessel: Brew Cauldron"), false);
            player.displayClientMessage(Component.literal("Contents: " + ForgeRegistries.BLOCKS.getKey(state.getBlock())), false);
            player.displayClientMessage(Component.literal("Tip: Water is required for brewing (for now)."), false);
        }
    }

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

            // Stop tracking if no longer brew water cauldron
            if (!(state.getBlock() instanceof BrewWaterCauldronBlock)) {
                it.remove();
                data.setDirty();
                continue;
            }

            int level = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                    ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                    : 0;

            if (level <= 0) {
                if (!v.ingredients.isEmpty() || v.doomed || v.matchedRecipeId != null || v.boiling || v.boilProgress != 0 || v.boilTicksRequired != 0) {
                    v.clearAll();
                    data.setDirty();
                }
                continue;
            }

            Integer required = HeatSourceManager.getBoilTicksFor(serverLevel, pos);
            if (required == null) {
                if (v.boilProgress != 0 || v.boiling || v.boilTicksRequired != 0) {
                    v.boilProgress = 0;
                    v.boilTicksRequired = 0;
                    v.boiling = false;
                    data.setDirty();
                }
                continue;
            }

            boolean wasBoiling = v.boiling;

            if (v.boilTicksRequired != required) {
                v.boilTicksRequired = required;
                data.setDirty();
            }

            if (!v.boiling) {
                v.boilProgress++;
                data.setDirty();

                if (v.boilProgress >= required) {
                    v.boiling = true;
                    data.setDirty();
                    serverLevel.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.6f, 1.0f);
                }
            }

            if (v.boiling) {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.9;
                double z = pos.getZ() + 0.5;

                serverLevel.sendParticles(ParticleTypes.BUBBLE, x, y, z, 2, 0.15, 0.05, 0.15, 0.01);

                if (!wasBoiling) {
                    serverLevel.sendParticles(ParticleTypes.BUBBLE_POP, x, y, z, 6, 0.20, 0.05, 0.20, 0.02);
                }

                if (v.doomed) {
                    serverLevel.sendParticles(ParticleTypes.SMOKE, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                    if ((serverLevel.getGameTime() & 3L) == 0L) {
                        serverLevel.sendParticles(ParticleTypes.ASH, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                    }
                } else if (v.matchedRecipeId != null) {
                    CauldronBrewRecipe recipe = null;
                    var opt = serverLevel.getRecipeManager().byKey(v.matchedRecipeId);
                    if (opt.isPresent() && opt.get() instanceof CauldronBrewRecipe r) {
                        recipe = r;
                    }

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
        }
    }
}
