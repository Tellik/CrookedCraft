package com.tellik.crookedcraft.brewing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

public final class ThermalTransformManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record DropDef(ResourceLocation itemId, int count) {}

    public record CoolingTransform(
            float atOrBelowTempC,
            ResourceLocation setBlockId,
            DropDef drop,
            boolean untrack,
            Integer requiredLevel,   // null = ignore
            Integer minLevel,        // null = ignore
            Integer maxLevel         // null = ignore
    ) {}


    private static volatile Map<Block, CoolingTransform> coolingByBlock = Collections.emptyMap();

    private ThermalTransformManager() {}

    public static void applyCoolingTransforms(Map<ResourceLocation, CoolingTransform> defs) {
        var resolved = new java.util.HashMap<Block, CoolingTransform>();

        for (var e : defs.entrySet()) {
            ResourceLocation fromId = e.getKey();
            CoolingTransform t = e.getValue();

            Block fromBlock = ForgeRegistries.BLOCKS.getValue(fromId);
            if (fromBlock == null) {
                LOGGER.warn("[crookedcraft] Unknown transform source block '{}' - skipping.", fromId);
                continue;
            }

            Block setBlock = ForgeRegistries.BLOCKS.getValue(t.setBlockId());
            if (setBlock == null) {
                LOGGER.warn("[crookedcraft] Unknown transform set_block '{}' for '{}' - skipping.", t.setBlockId(), fromId);
                continue;
            }

            if (!(t.atOrBelowTempC() > -100000f)) {
                LOGGER.warn("[crookedcraft] Invalid at_or_below_temp_c for '{}' - skipping.", fromId);
                continue;
            }

            if (t.drop != null) {
                Item dropItem = ForgeRegistries.ITEMS.getValue(t.drop.itemId());
                if (dropItem == null) {
                    LOGGER.warn("[crookedcraft] Unknown drop item '{}' for '{}' - skipping drop.", t.drop.itemId(), fromId);
                    // allow transform without drop
                }
            }

            resolved.put(fromBlock, t);
        }

        coolingByBlock = Collections.unmodifiableMap(resolved);
        LOGGER.info("[crookedcraft] Loaded cooling transforms: {}", coolingByBlock.size());
    }

    private static int getCauldronLevel(BlockState state) {
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_CAULDRON)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_CAULDRON);
        }
        return 0;
    }

    /**
     * Called from onLevelTick. If a transform applies, this method performs it and returns true.
     *
     * IMPORTANT: Because your tick loop is iterating the BrewingVesselData iterator, we accept:
     * - iterator to remove when untracking
     * - data to mark dirty
     */
    public static boolean tryApplyCoolingTransform(ServerLevel level,
                                                   BlockPos pos,
                                                   BlockState state,
                                                   BrewingVesselData.VesselState v,
                                                   Iterator<Map.Entry<Long, BrewingVesselData.VesselState>> dataIterator,
                                                   BrewingVesselData data) {

        CoolingTransform t = coolingByBlock.get(state.getBlock());
        if (t == null) return false;

        // Temperature condition
        if (v.tempC > t.atOrBelowTempC()) return false;

        // Level gating
        int lvl = getCauldronLevel(state);

        Integer requiredLevel = t.requiredLevel();
        if (requiredLevel != null && lvl != requiredLevel.intValue()) {
            return false;
        }

        Integer minLevel = t.minLevel();
        if (minLevel != null && lvl < minLevel.intValue()) {
            return false;
        }

        Integer maxLevel = t.maxLevel();
        if (maxLevel != null && lvl > maxLevel.intValue()) {
            return false;
        }

        // --- apply transform ---
        Block setBlock = ForgeRegistries.BLOCKS.getValue(t.setBlockId());
        if (setBlock == null) return false;

        level.setBlock(pos, setBlock.defaultBlockState(), 3);
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

        if (t.drop != null) {
            Item dropItem = ForgeRegistries.ITEMS.getValue(t.drop.itemId());
            if (dropItem != null) {
                int count = Math.max(1, t.drop.count());
                ItemStack dropStack = new ItemStack(dropItem, count);
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, dropStack);
            }
        }

        if (t.untrack) {
            dataIterator.remove();
        }

        data.setDirty();
        return true;
    }


    public static CoolingTransform getCoolingTransformFor(net.minecraft.world.level.block.Block block) {
        return coolingByBlock.get(block);
    }

}
