package com.tellik.crookedcraft.brewing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds datapack-driven heat definitions for Brewing.
 *
 * v1: We only support checking the position directly below the cauldron.
 * The heat value is "ticks required to reach boiling".
 */
public final class HeatSourceManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum ScanMode {
        BELOW_ONLY
    }

    public static final class HeatInfo {
        public final int boilTicksRequired;
        public final boolean isFluid;
        public final ResourceLocation sourceId;

        public HeatInfo(int boilTicksRequired, boolean isFluid, ResourceLocation sourceId) {
            this.boilTicksRequired = boilTicksRequired;
            this.isFluid = isFluid;
            this.sourceId = sourceId;
        }

        public String asDisplayString() {
            String kind = isFluid ? "Fluid" : "Block";
            return kind + ": " + sourceId + " (boil_ticks=" + boilTicksRequired + ")";
        }
    }

    private static volatile ScanMode scanMode = ScanMode.BELOW_ONLY;

    // Maps actual registry objects to boil ticks.
    private static volatile Map<Block, Integer> blockHeat = Collections.emptyMap();
    private static volatile Map<Fluid, Integer> fluidHeat = Collections.emptyMap();

    private HeatSourceManager() {}

    public static ScanMode getScanMode() {
        return scanMode;
    }

    public static void applyFromDatapack(ScanMode newMode, Map<ResourceLocation, Integer> blocks, Map<ResourceLocation, Integer> fluids) {
        Map<Block, Integer> resolvedBlocks = new HashMap<>();
        for (Map.Entry<ResourceLocation, Integer> e : blocks.entrySet()) {
            ResourceLocation id = e.getKey();
            int boilTicks = e.getValue();

            Block b = ForgeRegistries.BLOCKS.getValue(id);
            if (b == null) {
                LOGGER.warn("[crookedcraft] Unknown heat block id '{}' - ignoring.", id);
                continue;
            }
            resolvedBlocks.put(b, boilTicks);
        }

        Map<Fluid, Integer> resolvedFluids = new HashMap<>();
        for (Map.Entry<ResourceLocation, Integer> e : fluids.entrySet()) {
            ResourceLocation id = e.getKey();
            int boilTicks = e.getValue();

            Fluid f = ForgeRegistries.FLUIDS.getValue(id);
            if (f == null) {
                LOGGER.warn("[crookedcraft] Unknown heat fluid id '{}' - ignoring.", id);
                continue;
            }
            resolvedFluids.put(f, boilTicks);
        }

        scanMode = newMode;
        blockHeat = Collections.unmodifiableMap(resolvedBlocks);
        fluidHeat = Collections.unmodifiableMap(resolvedFluids);

        LOGGER.info("[crookedcraft] Loaded {} heat blocks and {} heat fluids. scan_mode={}",
                blockHeat.size(), fluidHeat.size(), scanMode);
    }

    /**
     * Returns boil ticks required for the heat source at the configured scan positions.
     * v1 scan_mode BELOW_ONLY: only checks cauldronPos.below().
     *
     * @return null if no heat source found.
     */
    public static Integer getBoilTicksFor(Level level, BlockPos cauldronPos) {
        HeatInfo info = getHeatInfo(level, cauldronPos);
        return info == null ? null : info.boilTicksRequired;
    }

    /**
     * Returns detailed heat info for status/debug readouts.
     *
     * @return null if no heat source found.
     */
    public static HeatInfo getHeatInfo(Level level, BlockPos cauldronPos) {
        // v1 only supports below-only.
        BlockPos heatPos = cauldronPos.below();

        FluidState fs = level.getFluidState(heatPos);
        if (!fs.isEmpty()) {
            Fluid f = fs.getType();
            Integer ticks = fluidHeat.get(f);
            if (ticks != null) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(f);
                if (id == null) id = ResourceLocation.fromNamespaceAndPath("minecraft", "unknown");
                return new HeatInfo(ticks, true, id);
            }
        }

        Block b = level.getBlockState(heatPos).getBlock();
        Integer ticks = blockHeat.get(b);
        if (ticks != null) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
            if (id == null) id = ResourceLocation.fromNamespaceAndPath("minecraft", "unknown");
            return new HeatInfo(ticks, false, id);
        }

        return null;
    }
}
