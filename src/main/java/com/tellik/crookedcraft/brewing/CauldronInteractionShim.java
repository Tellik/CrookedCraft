package com.tellik.crookedcraft.brewing;

import com.mojang.logging.LogUtils;
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

import java.util.Map;
import java.util.Objects;

import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;


/**
 * Hooks vanilla cauldron fill interactions (which do NOT reliably fire RightClickBlock).

 * We wrap the existing EMPTY/WATER interaction entries and delegate to vanilla first,
 * then (if the block state became WATER_CAULDRON) we "arm" our brewing tracking so the
 * boiling tick loop runs without requiring any further player interaction.

 * This keeps cauldrons vanilla, but makes Brewing v1 automation-friendly and reliable.
 */
public final class CauldronInteractionShim {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean installed = false;

    private CauldronInteractionShim() {}

    public static void install() {
        if (installed) return;
        installed = true;

        int wrapped = 0;

        // 1) Empty cauldron + water bucket -> becomes WATER_CAULDRON
        wrapped += wrapInteraction(CauldronInteraction.EMPTY, Items.WATER_BUCKET);

        // 2) Optional: empty cauldron + water potion bottle can add water (vanilla behavior)
        // The map key is Items.POTION (item type), so this wrapper will run for ANY potion.
        // We only arm brewing if the resulting block state is WATER_CAULDRON, so it's safe.
        wrapped += wrapInteraction(CauldronInteraction.EMPTY, Items.POTION);

        // 3) Optional: WATER cauldron + water potion bottle (increase level)
        wrapped += wrapInteraction(CauldronInteraction.WATER, Items.POTION);

        LOGGER.info("[crookedcraft] CauldronInteraction shim installed. Wrapped {} interaction(s).", wrapped);
    }

    private static int wrapInteraction(Map<Item, CauldronInteraction> map, Item keyItem) {
        CauldronInteraction original = map.get(keyItem);
        if (original == null) {
            LOGGER.warn("[crookedcraft] CauldronInteraction shim: no existing interaction found for item {} in map {}",
                    keyItem, mapName(map));
            return 0;
        }

        // Prevent double wrapping if something calls install twice or mods reorder.
        if (original instanceof WrappedInteraction) {
            return 0;
        }

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
        public InteractionResult interact(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
            // Delegate to vanilla first (this performs the actual fill / level change)
            InteractionResult result = delegate.interact(state, level, pos, player, hand, stack);

            // Only server does authoritative tracking
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                // If after vanilla interaction we have a water cauldron at pos, arm brewing
                BrewingVesselHooks.onMaybeWaterCauldronChanged(serverLevel, pos);
            }

            return result;
        }
    }
}
