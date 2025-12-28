package com.tellik.crookedcraft.brewing;

import com.mojang.logging.LogUtils;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps VANILLA cauldron interaction tables so vanilla fill logic runs,
 * then (optionally) converts the resulting vanilla cauldron state into CrookedCraft brew variants
 * and arms thermal tracking.
 *
 * IMPORTANT:
 * This shim MUST NOT affect vanilla cauldrons globally.
 * It is only allowed to post-process interactions that started on a CrookedCraft brew vessel.
 */
public final class CauldronInteractionShim {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean installed = false;

    private CauldronInteractionShim() {}

    public static void install() {
        if (installed) return;
        installed = true;

        int wrapped = 0;

        // EMPTY -> (water/lava/snow) fills
        wrapped += wrapInteraction(CauldronInteraction.EMPTY, Items.WATER_BUCKET);
        wrapped += wrapInteraction(CauldronInteraction.EMPTY, Items.LAVA_BUCKET);
        wrapped += wrapInteraction(CauldronInteraction.EMPTY, Items.POWDER_SNOW_BUCKET);

        // potion fill on empty (water bottle)
        wrapped += wrapInteraction(CauldronInteraction.EMPTY, Items.POTION);

        // WATER level increases (water bottle / water bucket)
        wrapped += wrapInteraction(CauldronInteraction.WATER, Items.POTION);
        wrapped += wrapInteraction(CauldronInteraction.WATER, Items.WATER_BUCKET);

        // POWDER_SNOW level increases (powder snow bucket)
        wrapped += wrapInteraction(CauldronInteraction.POWDER_SNOW, Items.POWDER_SNOW_BUCKET);

        // LAVA "already full" (lava bucket interaction)
        wrapped += wrapInteraction(CauldronInteraction.LAVA, Items.LAVA_BUCKET);

        LOGGER.info("[crookedcraft] CauldronInteraction shim installed. Wrapped {} interaction(s).", wrapped);
    }

    private static int wrapInteraction(Map<Item, CauldronInteraction> map, Item keyItem) {
        CauldronInteraction original = map.get(keyItem);
        if (original == null) {
            LOGGER.warn("[crookedcraft] CauldronInteraction shim: no existing interaction found for item {} in map {}",
                    keyItem, mapName(map));
            return 0;
        }

        if (original instanceof WrappedInteraction) return 0;

        map.put(keyItem, new WrappedInteraction(original));
        return 1;
    }

    private static String mapName(Map<Item, CauldronInteraction> map) {
        if (map == CauldronInteraction.EMPTY) return "EMPTY";
        if (map == CauldronInteraction.WATER) return "WATER";
        if (map == CauldronInteraction.LAVA) return "LAVA";
        if (map == CauldronInteraction.POWDER_SNOW) return "POWDER_SNOW";
        return "UNKNOWN";
    }

    private static final class WrappedInteraction implements CauldronInteraction {
        private final CauldronInteraction delegate;

        private WrappedInteraction(CauldronInteraction delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        @MethodsReturnNonnullByDefault
        @ParametersAreNonnullByDefault
        public InteractionResult interact(BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, ItemStack stack) {

            // Capture whether this interaction STARTED on one of our brew vessels.
            // This prevents vanilla cauldrons from ever being post-processed into brew variants.
            final boolean startedOnBrewVessel = state.is(ModTags.Blocks.BREW_VESSELS);

            // 1) Let vanilla do the actual fill/level swap
            InteractionResult result = delegate.interact(state, level, pos, player, hand, stack);

            // 2) Server-side: ONLY if this started on a brew vessel do we post-process.
            if (startedOnBrewVessel && !level.isClientSide && level instanceof ServerLevel serverLevel) {
                BrewingVesselHooks.onAfterVanillaCauldronInteraction(serverLevel, pos);
            }

            return result;
        }
    }
}
