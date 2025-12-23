package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.Level;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

            sendStatus(player, serverLevel, pos, state);
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

        // Brewing logic is water-only right now.
        if (!(state.getBlock() instanceof BrewWaterCauldronBlock)) {
            return;
        }

        // If we're going to handle this, cancel client-side to stop predicted placement flicker.
        if (level.isClientSide) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // --- SERVER from here ---
        ServerLevel serverLevel = (ServerLevel) level;

        int cauldronLevel = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                : 0;

        if (cauldronLevel <= 0) {
            return;
        }

        long posLong = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        data.ensureTracked(posLong);
        BrewingVesselData.VesselState v = data.getTrackedState(posLong);

        HeatSourceManager.HeatInfo heat = HeatSourceManager.getHeatInfo(serverLevel, pos);
        if (heat != null && v.boilTicksRequired <= 0) {
            v.boilTicksRequired = heat.boilTicksRequired;
            data.setDirty();
        }

        // Keep tint synced
        syncBrewTintIfNeeded(serverLevel, pos, state, v);

        // -------------------------
        // 3a) Bottling / extraction
        // -------------------------
        if (held.is(ModTags.Items.BREW_CONTAINERS)) {

            // DOOMED => Black Sludge
            if (v.doomed) {
                ItemStack out = new ItemStack(ModBrewingItems.BLACK_SLUDGE.get());

                // VANILLA-STYLE delivery to avoid silent deletion:
                // - If held stack is size 1, replace in-hand with the result
                // - If held stack > 1, shrink 1 and insert/drop result
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

            // VANILLA-STYLE delivery to avoid silent deletion
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
     *
     * This is the most reliable way to avoid "full inventory silently deletes output".
     */
    private static void deliverResultLikeVanilla(Player player,
                                                 InteractionHand hand,
                                                 ItemStack heldContainer,
                                                 ItemStack result,
                                                 ServerLevel level,
                                                 BlockPos pos) {

        if (result.isEmpty()) return;

        // Always work with a copy so nothing upstream can mutate the recipe output reference.
        ItemStack out = result.copy();

        // --- Case A: exactly 1 container in-hand -> consume it by clearing the hand,
        // then give the result back into that now-empty hand slot (main hand).
        if (heldContainer.getCount() == 1) {

            if (hand == InteractionHand.MAIN_HAND) {
                int slot = player.getInventory().selected;

                // Consume the container (it becomes the output).
                player.getInventory().setItem(slot, ItemStack.EMPTY);

                // Forge helper: inserts; if it can't, it drops at player's position.
                ItemHandlerHelper.giveItemToPlayer(player, out, slot);
            } else {
                // Offhand case (if you ever allow it): clear offhand then give.
                player.setItemInHand(hand, ItemStack.EMPTY);
                ItemHandlerHelper.giveItemToPlayer(player, out);
            }

            // Force an inventory sync to avoid client-side "ghost" states.
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return;
        }

        // --- Case B: container stack > 1 -> consume one, then give the output.
        heldContainer.shrink(1);

        // Inserts; if it can't, drops at player's position.
        ItemHandlerHelper.giveItemToPlayer(player, out);

        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }


    private static void handleDiscard(ServerLevel serverLevel, BlockPos pos, BlockState state, Player player, PlayerInteractEvent.RightClickBlock event) {
        // If truly empty brew cauldron: don't claim discard.
        if (state.is(ModBrewingBlocks.BREW_CAULDRON.get())) {
            player.displayClientMessage(Component.literal("The cauldron is already empty."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        BrewingVesselData.VesselState v = data.getStateIfTracked(pos.asLong());

        // DOOMED discard chance drop
        if (v != null && v.doomed) {
            float chance = 0.10f; // tune later
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

    private static void sendStatus(Player player, ServerLevel level, BlockPos pos, BlockState state) {
        player.displayClientMessage(Component.literal("=== CrookedCraft Brewing ==="), false);

        HeatSourceManager.HeatInfo heat = HeatSourceManager.getHeatInfo(level, pos);
        boolean hasHeat = (heat != null);

        VesselInfo vessel = VesselInfo.fromState(state);
        BrewingVesselData data = BrewingVesselData.get(level);
        BrewingVesselData.VesselState v = (vessel.type == VesselType.WATER) ? data.getStateIfTracked(pos.asLong()) : null;

        List<String> phases = computePhases(vessel, hasHeat, v);
        player.displayClientMessage(Component.literal("Phase: " + String.join(" + ", phases)), false);

        player.displayClientMessage(Component.literal("Vessel: " + vessel.displayName), false);
        player.displayClientMessage(Component.literal("Heat: " + (hasHeat ? heat.asDisplayString() : "None")), false);

        if (vessel.type != VesselType.WATER) return;

        if (v == null || v.ingredients.isEmpty()) {
            player.displayClientMessage(Component.literal("Ingredients: (none)"), false);
        } else {
            player.displayClientMessage(Component.literal("Ingredients:"), false);
            for (Map.Entry<ResourceLocation, Integer> e : v.ingredients.entrySet()) {
                player.displayClientMessage(Component.literal(" - " + e.getKey() + " x" + e.getValue()), false);
            }
        }

        if (v != null && v.matchedRecipeId != null) {
            player.displayClientMessage(Component.literal("Matched: " + v.matchedRecipeId), false);
            player.displayClientMessage(Component.literal("Use a brew container to collect."), false);
        } else if (v != null && v.doomed) {
            player.displayClientMessage(Component.literal("Status: DOOMED"), false);
            player.displayClientMessage(Component.literal("Discard: sneak + empty hand."), false);
        } else {
            player.displayClientMessage(Component.literal("Tip: Sneak + empty hand to discard."), false);
        }
    }

    private static List<String> computePhases(VesselInfo vessel, boolean hasHeat, BrewingVesselData.VesselState v) {
        List<String> out = new ArrayList<>();

        if (vessel.type == VesselType.EMPTY) {
            out.add(hasHeat ? "IDLE" : "EMPTY");
            return out;
        }

        boolean hasPotion = (v != null && v.matchedRecipeId != null);
        boolean hasDoomed = (v != null && v.doomed);
        boolean hasAnyBrewState = hasPotion || hasDoomed;

        if (!hasHeat) {
            out.add(hasAnyBrewState ? "COOLING" : "COLD");
            if (hasPotion) out.add("POTION(" + vessel.level + ")");
            return out;
        }

        boolean isBoiling = (v != null && v.boiling);
        out.add(isBoiling ? "BOILING" : "HEATING");

        if (hasPotion) out.add("POTION(" + vessel.level + ")");
        else if (isBoiling && v != null && !v.ingredients.isEmpty()) out.add("MIXING");

        return out;
    }

    private static void recordIngredient(BrewingVesselData.VesselState v, Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) return;
        v.ingredients.put(id, v.ingredients.getOrDefault(id, 0) + 1);
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

            if (!(state.getBlock() instanceof BrewWaterCauldronBlock)) {
                it.remove();
                data.setDirty();
                continue;
            }

            int level = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                    ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                    : 0;

            syncBrewTintIfNeeded(serverLevel, pos, state, v);

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
                    if (opt.isPresent() && opt.get() instanceof CauldronBrewRecipe r) recipe = r;

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

    private enum VesselType { EMPTY, WATER, LAVA, POWDER_SNOW }

    private static final class VesselInfo {
        final VesselType type;
        final int level;
        final String displayName;

        private VesselInfo(VesselType type, int level, String displayName) {
            this.type = type;
            this.level = level;
            this.displayName = displayName;
        }

        static VesselInfo fromState(BlockState state) {
            if (state.getBlock() instanceof BrewWaterCauldronBlock) {
                int lvl = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON) ? state.getValue(BlockStateProperties.LEVEL_CAULDRON) : 0;
                return new VesselInfo(VesselType.WATER, lvl, "Brew Cauldron (Water " + lvl + "/3)");
            }

            if (state.is(ModBrewingBlocks.BREW_LAVA_CAULDRON.get())) {
                return new VesselInfo(VesselType.LAVA, 3, "Brew Cauldron (Lava)");
            }

            if (state.getBlock() instanceof BrewPowderSnowCauldronBlock) {
                int lvl = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON) ? state.getValue(BlockStateProperties.LEVEL_CAULDRON) : 0;
                return new VesselInfo(VesselType.POWDER_SNOW, lvl, "Brew Cauldron (Powder Snow " + lvl + "/3)");
            }

            return new VesselInfo(VesselType.EMPTY, 0, "Brew Cauldron (Empty)");
        }
    }
}
