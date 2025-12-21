package com.tellik.crookedcraft.brewing;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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
    private static final String MSG_RUINED = "The brew is ruined. Discard it (sneak + empty hand).";

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HeatSourceReloadListener());
    }

    @SubscribeEvent
    public static void onRightClickCauldron(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getPos();
        BlockState clickedState = serverLevel.getBlockState(pos);
        Player player = event.getEntity();

        // Brewing interactions are MAIN_HAND only to avoid double-processing/double messages.
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        if (!clickedState.is(ModTags.Blocks.BREW_VESSELS)) return;
        if (!clickedState.is(Blocks.WATER_CAULDRON)) return;

        long posLong = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(serverLevel);

        data.ensureTracked(posLong);
        BrewingVesselData.VesselState v = data.getTrackedState(posLong);

        int level = clickedState.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                ? clickedState.getValue(BlockStateProperties.LEVEL_CAULDRON)
                : 0;

        HeatSourceManager.HeatInfo heat = HeatSourceManager.getHeatInfo(serverLevel, pos);
        if (heat != null && v.boilTicksRequired <= 0) {
            v.boilTicksRequired = heat.boilTicksRequired;
            data.setDirty();
        }

        ItemStack held = player.getMainHandItem();

        // Empty-hand: status / discard
        if (held.isEmpty()) {
            if (player.isShiftKeyDown()) {
                serverLevel.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), 3);
                data.untrack(posLong);

                player.displayClientMessage(Component.literal("You discard the cauldron's contents."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            sendStatus(player, level, heat, v, serverLevel);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Bottle/container extraction (only when READY)
        if (held.is(ModTags.Items.BREW_CONTAINERS)) {
            if (v.doomed) {
                player.displayClientMessage(Component.literal(MSG_RUINED), false);
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
                player.displayClientMessage(Component.literal("Recipe missing after reload. The brew destabilizes and fails."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            if (level <= 0) {
                serverLevel.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), 3);
                data.untrack(posLong);
                player.displayClientMessage(Component.literal("The cauldron is empty."), false);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }

            held.shrink(1);

            ItemStack out = recipe.createResultStack();
            if (!player.getInventory().add(out)) {
                player.drop(out, false);
            }

            serverLevel.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7f, 1.0f);

            int newLevel = Math.max(0, level - 1);
            if (newLevel <= 0) {
                serverLevel.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), 3);
                data.untrack(posLong);
            } else {
                serverLevel.setBlock(pos, clickedState.setValue(BlockStateProperties.LEVEL_CAULDRON, newLevel), 3);
                data.setDirty();
            }

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Ingredient interactions
        if (v.doomed) {
            player.displayClientMessage(Component.literal(MSG_RUINED), false);
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

        // If READY and they add anything, ruin it (consumes 1)
        if (v.matchedRecipeId != null) {
            held.shrink(1);
            recordIngredient(v, ingredientItem);
            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();

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

        held.shrink(1);
        recordIngredient(v, ingredientItem);

        if (res.outcome == BrewingMatcher.AddOutcome.DOOMED) {
            v.doomed = true;
            v.matchedRecipeId = null;
            data.setDirty();

            serverLevel.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.6f, 0.8f);
            player.displayClientMessage(Component.literal("The brew curdles and fails."), false);

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        if (res.outcome == BrewingMatcher.AddOutcome.READY) {
            v.matchedRecipeId = res.matchedRecipeId;
            data.setDirty();

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

    private static void sendStatus(Player player, int level, HeatSourceManager.HeatInfo heat, BrewingVesselData.VesselState v, ServerLevel serverLevel) {
        player.displayClientMessage(Component.literal("=== CrookedCraft Brewing (Milestone 2) ==="), false);
        player.displayClientMessage(Component.literal("Liquid: Water (level " + level + ")"), false);

        if (heat == null) {
            player.displayClientMessage(Component.literal("Heat: None detected below the cauldron."), false);
        } else {
            player.displayClientMessage(Component.literal("Heat: " + heat.asDisplayString()), false);
        }

        if (level <= 0) {
            player.displayClientMessage(Component.literal("Boil: No liquid."), false);
        } else if (v.boiling) {
            player.displayClientMessage(Component.literal("Boil: BOILING"), false);
        } else if (heat == null) {
            player.displayClientMessage(Component.literal("Boil: Not heating."), false);
        } else {
            int req = heat.boilTicksRequired > 0 ? heat.boilTicksRequired : Math.max(v.boilTicksRequired, 1);
            int prog = Math.max(v.boilProgress, 0);
            player.displayClientMessage(Component.literal("Boil: Heating (" + prog + " / " + req + " ticks)"), false);
        }

        String phase;
        if (v.doomed) phase = "DOOMED";
        else if (v.matchedRecipeId != null) phase = "READY";
        else if (v.boiling) phase = "BREWING";
        else phase = "HEATING";

        player.displayClientMessage(Component.literal("Phase: " + phase), false);

        int candidates = BrewingMatcher.filterCandidates(serverLevel, BrewingMatcher.WATER_LIQUID, v.ingredients).size();
        player.displayClientMessage(Component.literal("Candidates: " + candidates), false);

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

            // Stop tracking if cauldron is no longer water
            if (!state.is(Blocks.WATER_CAULDRON)) {
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
                        float r = ((color >> 16) & 0xFF) / 255.0f;
                        float g = ((color >> 8) & 0xFF) / 255.0f;
                        float b = (color & 0xFF) / 255.0f;

                        DustParticleOptions dust = new DustParticleOptions(new Vector3f(r, g, b), 0.9f);
                        serverLevel.sendParticles(dust, x, y, z, 1, 0.12, 0.02, 0.12, 0.01);
                    }
                }
            }
        }
    }
}
