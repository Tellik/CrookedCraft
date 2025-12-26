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
 * Datapack-driven heat definitions for Brewing (THERMAL ONLY).
 *
 * - maxTempC: equilibrium/target temperature achievable by the source
 * - heatPerTickC: approach rate toward maxTempC (always positive; maxTempC may be negative)
 *
 * No legacy boil ticks remain in this system.
 */
public final class HeatSourceManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum ScanMode {
        BELOW_ONLY
    }

    public record HeatProfile(float maxTempC, float heatPerTickC) {}

    public static final class HeatInfo {
        public final boolean isFluid;
        public final ResourceLocation sourceId;
        public final HeatProfile profile;

        public HeatInfo(boolean isFluid, ResourceLocation sourceId, HeatProfile profile) {
            this.isFluid = isFluid;
            this.sourceId = sourceId;
            this.profile = profile;
        }

        public String asDisplayString() {
            String kind = isFluid ? "Fluid" : "Block";
            String therm = (profile != null)
                    ? String.format("max=%.1fC, dT=%.3fC/t", profile.maxTempC(), profile.heatPerTickC())
                    : "thermal=-";
            return kind + ": " + sourceId + " (" + therm + ")";
        }
    }

    private static volatile ScanMode scanMode = ScanMode.BELOW_ONLY;

    private static volatile Map<Block, HeatProfile> blockProfiles = Collections.emptyMap();
    private static volatile Map<Fluid, HeatProfile> fluidProfiles = Collections.emptyMap();

    private HeatSourceManager() {}

    public static ScanMode getScanMode() {
        return scanMode;
    }

    /**
     * Apply datapack values.
     * @param newMode scan mode
     * @param blocks map of block id -> entry
     * @param fluids map of fluid id -> entry
     * @param heatPerTickScale global scale applied to all heatPerTickC
     */
    public static void applyFromDatapackThermal(
            ScanMode newMode,
            Map<ResourceLocation, HeatEntry> blocks,
            Map<ResourceLocation, HeatEntry> fluids,
            float heatPerTickScale
    ) {
        Map<Block, HeatProfile> resolvedBlockProfiles = new HashMap<>();
        Map<Fluid, HeatProfile> resolvedFluidProfiles = new HashMap<>();

        // Blocks
        for (Map.Entry<ResourceLocation, HeatEntry> e : blocks.entrySet()) {
            ResourceLocation id = e.getKey();
            HeatEntry he = e.getValue();

            Block b = ForgeRegistries.BLOCKS.getValue(id);
            if (b == null) {
                LOGGER.warn("[crookedcraft] Unknown heat block id '{}' - ignoring.", id);
                continue;
            }

            if (he.profile != null) {
                HeatProfile p = he.profile;
                HeatProfile scaled = new HeatProfile(p.maxTempC(), p.heatPerTickC() * heatPerTickScale);
                resolvedBlockProfiles.put(b, scaled);
            }
        }

        // Fluids
        for (Map.Entry<ResourceLocation, HeatEntry> e : fluids.entrySet()) {
            ResourceLocation id = e.getKey();
            HeatEntry he = e.getValue();

            Fluid f = ForgeRegistries.FLUIDS.getValue(id);
            if (f == null) {
                LOGGER.warn("[crookedcraft] Unknown heat fluid id '{}' - ignoring.", id);
                continue;
            }

            if (he.profile != null) {
                HeatProfile p = he.profile;
                HeatProfile scaled = new HeatProfile(p.maxTempC(), p.heatPerTickC() * heatPerTickScale);
                resolvedFluidProfiles.put(f, scaled);
            }
        }

        scanMode = newMode;
        blockProfiles = Collections.unmodifiableMap(resolvedBlockProfiles);
        fluidProfiles = Collections.unmodifiableMap(resolvedFluidProfiles);

        LOGGER.info("[crookedcraft] Loaded heat sources: profiles(blocks={} fluids={}) scan_mode={} heat_per_tick_scale={}",
                blockProfiles.size(), fluidProfiles.size(), scanMode, heatPerTickScale);
    }

    /** New API: returns a thermal profile if defined, else null. */
    public static HeatProfile getHeatProfile(Level level, BlockPos cauldronPos) {
        HeatInfo info = getHeatInfo(level, cauldronPos);
        return (info == null) ? null : info.profile;
    }

    /**
     * Returns detailed heat info for status/debug.
     * scan_mode BELOW_ONLY: only checks cauldronPos.below().
     *
     * @return null if no heat source found
     */
    public static HeatInfo getHeatInfo(Level level, BlockPos cauldronPos) {
        BlockPos heatPos = cauldronPos.below();

        // Prefer fluid if present
        FluidState fs = level.getFluidState(heatPos);
        if (!fs.isEmpty()) {
            Fluid f = fs.getType();
            HeatProfile profile = fluidProfiles.get(f);
            if (profile != null) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(f);
                if (id == null) id = ResourceLocation.fromNamespaceAndPath("minecraft", "unknown");
                return new HeatInfo(true, id, profile);
            }
        }

        // Then block
        Block b = level.getBlockState(heatPos).getBlock();
        HeatProfile profile = blockProfiles.get(b);
        if (profile != null) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
            if (id == null) id = ResourceLocation.fromNamespaceAndPath("minecraft", "unknown");
            return new HeatInfo(false, id, profile);
        }

        return null;
    }

    /** Parsed entry from datapack. */
    public static final class HeatEntry {
        public final HeatProfile profile; // required in thermal-only system

        public HeatEntry(HeatProfile profile) {
            this.profile = profile;
        }
    }
}
