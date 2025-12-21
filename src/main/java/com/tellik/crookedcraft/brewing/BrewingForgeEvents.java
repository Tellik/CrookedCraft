package com.tellik.crookedcraft.brewing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrewingForgeEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HeatSourceReloadListener());
    }

    /**
     * Milestone 1 interaction rules:
     * - Only WATER cauldrons tagged as brew_vessels are eligible.
     * - Empty-hand + Right Click (NOT sneaking): shows a detailed status message.
     *   - If not tracked yet, it will begin tracking (arming).
     * - Empty-hand + Sneak + Right Click: discards contents (empties cauldron + untracks).
     *
     * IMPORTANT: We only handle MAIN_HAND to avoid duplicate processing (offhand event).
     */
    @SubscribeEvent
    public static void onRightClickCauldron(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockPos pos = event.getPos();
        BlockState state = serverLevel.getBlockState(pos);

        // Only respond to blocks datapacked as brew vessels.
        if (!state.is(ModTags.Blocks.BREW_VESSELS)) return;

        // v1: only water cauldrons are brewable.
        if (!state.is(Blocks.WATER_CAULDRON)) return;

        Player player = event.getEntity();
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);

        // v1: only empty hand interactions control brewing state/status.
        if (!held.isEmpty()) return;

        long posLong = pos.asLong();
        BrewingVesselData data = BrewingVesselData.get(serverLevel);

        // Sneak + right click: discard/clear
        if (player.isShiftKeyDown()) {
            serverLevel.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), 3);
            data.untrack(posLong);

            player.displayClientMessage(Component.literal("You discard the cauldron's contents."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        // Non-sneak right click: show status. Also auto-track if not already tracked.
        boolean newlyTracked = false;
        if (!data.isTracked(posLong)) {
            data.track(posLong);
            newlyTracked = true;
        }

        BrewingVesselData.VesselState v = data.getState(posLong);
        if (v == null) {
            player.displayClientMessage(Component.literal("Brewing status unavailable (internal error)."), false);
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        int level = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                : 0;

        HeatSourceManager.HeatInfo heat = HeatSourceManager.getHeatInfo(serverLevel, pos);

        // If we have heat but required ticks isn't initialized yet (common right after tracking),
        // seed it so the status message is correct immediately, before the next tick runs.
        if (heat != null && v.boilTicksRequired <= 0) {
            v.boilTicksRequired = heat.boilTicksRequired;
            data.setDirty();
        }

        // Build a multi-line status readout in chat.
        player.displayClientMessage(Component.literal("=== CrookedCraft Brewing (Milestone 1) ==="), false);

        if (newlyTracked) {
            player.displayClientMessage(Component.literal("Status: Prepared for brewing."), false);
        } else {
            player.displayClientMessage(Component.literal("Status: Tracking active."), false);
        }

        player.displayClientMessage(Component.literal("Liquid: Water (level " + level + ")"), false);

        if (heat == null) {
            player.displayClientMessage(Component.literal("Heat: None detected below the cauldron."), false);
        } else {
            player.displayClientMessage(Component.literal("Heat: " + heat.asDisplayString()), false);
        }

        if (level <= 0) {
            player.displayClientMessage(Component.literal("Boil: No liquid."), false);
        } else if (heat == null) {
            player.displayClientMessage(Component.literal("Boil: Not heating."), false);
        } else if (v.boiling) {
            player.displayClientMessage(Component.literal("Boil: BOILING"), false);
        } else {
            // Prefer the live heat value, fall back to stored required ticks if present.
            int req = heat.boilTicksRequired > 0 ? heat.boilTicksRequired : Math.max(v.boilTicksRequired, 1);
            int prog = Math.max(v.boilProgress, 0);
            player.displayClientMessage(Component.literal("Boil: Heating (" + prog + " / " + req + " ticks)"), false);
        }

        // Ingredients not implemented until Milestone 2.
        player.displayClientMessage(Component.literal("Ingredients: (none)"), false);

        player.displayClientMessage(Component.literal("Tip: Sneak + Right Click (empty hand) to discard."), false);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * Server-authoritative ticking: update tracked cauldrons.
     */
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

            // If chunk not loaded, skip (don't discard tracked state; player can come back).
            if (!serverLevel.isLoaded(pos)) continue;

            BlockState state = serverLevel.getBlockState(pos);

            // If the vessel is no longer a water cauldron, stop tracking.
            if (!state.is(Blocks.WATER_CAULDRON)) {
                it.remove();
                data.setDirty();
                continue;
            }

            int level = state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)
                    ? state.getValue(BlockStateProperties.LEVEL_CAULDRON)
                    : 0;

            if (level <= 0) {
                // No liquid: reset state.
                if (v.boilProgress != 0 || v.boiling || v.boilTicksRequired != 0) {
                    v.boilProgress = 0;
                    v.boilTicksRequired = 0;
                    v.boiling = false;
                    data.setDirty();
                }
                continue;
            }

            Integer required = HeatSourceManager.getBoilTicksFor(serverLevel, pos);
            if (required == null) {
                // No heat: reset progress and boiling.
                if (v.boilProgress != 0 || v.boiling || v.boilTicksRequired != 0) {
                    v.boilProgress = 0;
                    v.boilTicksRequired = 0;
                    v.boiling = false;
                    data.setDirty();
                }
                continue;
            }

            // Heat present.
            boolean wasBoiling = v.boiling;

            // Keep required ticks current (datapack reload can change values).
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

                    // One-time sound cue at boil start (server broadcast).
                    serverLevel.playSound(
                            null,
                            pos,
                            SoundEvents.BREWING_STAND_BREW,
                            SoundSource.BLOCKS,
                            0.6f,
                            1.0f
                    );
                }
            }

            // Emit particles while boiling.
            if (v.boiling) {
                double x = pos.getX() + 0.5;
                double y = pos.getY() + 0.9;
                double z = pos.getZ() + 0.5;

                serverLevel.sendParticles(ParticleTypes.BUBBLE, x, y, z, 2, 0.15, 0.05, 0.15, 0.01);

                // If it just started boiling, also do a slightly stronger burst.
                if (!wasBoiling) {
                    serverLevel.sendParticles(ParticleTypes.BUBBLE_POP, x, y, z, 6, 0.20, 0.05, 0.20, 0.02);
                }
            }
        }
    }
}
