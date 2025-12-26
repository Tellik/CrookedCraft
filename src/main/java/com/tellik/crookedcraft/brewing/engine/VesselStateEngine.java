package com.tellik.crookedcraft.brewing.engine;

import com.tellik.crookedcraft.brewing.BrewingVesselData;
import com.tellik.crookedcraft.brewing.HeatSourceManager;
import com.tellik.crookedcraft.brewing.cauldron.BrewCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Thermal-only server-side sync.
 *
 * - Keeps legacy boil fields inert (boilProgress/boilTicksRequired always 0)
 * - Sets v.boiling based on current v.tempC and heat profile presence
 *
 * This avoids any old "boil tick" logic fighting the thermal system.
 */
public final class VesselStateEngine {
    private VesselStateEngine() {}

    private static final float WATER_BOIL_C = 100.0f;
    private static final float LAVA_BOIL_C  = 1000.0f;

    public static void tick(ServerLevel level) {
        BrewingVesselData data = BrewingVesselData.get(level);

        long[] keys = getTrackedKeysBestEffort(data);
        if (keys.length == 0) return;

        boolean changedAny = false;

        for (long key : keys) {
            BlockPos pos = BlockPos.of(key);
            BlockState state = level.getBlockState(pos);

            if (!isAnyBrewCauldron(state)) {
                data.untrack(key);
                changedAny = true;
                continue;
            }

            BrewingVesselData.VesselState v = data.getTrackedState(key);
            if (v == null) {
                data.ensureTracked(key);
                v = data.getTrackedState(key);
                if (v == null) continue;
                changedAny = true;
            }

            // Legacy fields inert
            if (v.boilProgress != 0 || v.boilTicksRequired != 0) {
                v.boilProgress = 0;
                v.boilTicksRequired = 0;
                changedAny = true;
            }

            boolean isWater = state.getBlock() instanceof BrewWaterCauldronBlock;
            boolean isSnow  = state.getBlock() instanceof BrewPowderSnowCauldronBlock;
            boolean isLava  = state.getBlock() instanceof BrewLavaCauldronBlock;

            // If temp isn't initialized yet, don't force any boil state here.
            if (Float.isNaN(v.tempC)) {
                if (v.boiling) {
                    v.boiling = false;
                    changedAny = true;
                }
                continue;
            }

            HeatSourceManager.HeatProfile heat = HeatSourceManager.getHeatProfile(level, pos);

            int cauldronLevel = 0;
            if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
                cauldronLevel = state.getValue(LayeredCauldronBlock.LEVEL);
            }

            // Strength bonus only affects the effective max achievable
            float strengthBonus = getStrengthBonus(cauldronLevel);

            boolean newBoiling;
            if (isWater) {
                // Can boil only if a source exists whose max can reach boiling (after strength bonus)
                boolean canBoil = heat != null && (heat.maxTempC() * strengthBonus) >= WATER_BOIL_C;
                newBoiling = canBoil && (v.tempC >= WATER_BOIL_C - 0.001f);
            } else if (isLava) {
                // Lava "boiling" is a temperature concept only
                newBoiling = (v.tempC >= LAVA_BOIL_C - 0.001f);
            } else if (isSnow) {
                // Snow isn't "boiling" in gameplay
                newBoiling = false;
            } else {
                newBoiling = false;
            }

            if (v.boiling != newBoiling) {
                v.boiling = newBoiling;
                changedAny = true;
            }
        }

        if (changedAny) data.setDirty();
    }

    private static float getStrengthBonus(int cauldronLevel) {
        if (cauldronLevel == 1) return 1.25f;
        if (cauldronLevel == 2) return 1.10f;
        return 1.0f;
    }

    private static boolean isAnyBrewCauldron(BlockState state) {
        return state.getBlock() instanceof BrewCauldronBlock
                || state.getBlock() instanceof BrewWaterCauldronBlock
                || state.getBlock() instanceof BrewLavaCauldronBlock
                || state.getBlock() instanceof BrewPowderSnowCauldronBlock;
    }

    private static long[] getTrackedKeysBestEffort(BrewingVesselData data) {
        try {
            Field candidate = null;
            for (Field f : data.getClass().getDeclaredFields()) {
                String tn = f.getType().getName();
                if (tn.contains("Long2Object") || tn.contains("Long2ObjectOpenHashMap")) {
                    candidate = f;
                    break;
                }
            }
            if (candidate == null) return new long[0];

            candidate.setAccessible(true);
            Object map = candidate.get(data);
            if (map == null) return new long[0];

            Method keySet = map.getClass().getMethod("keySet");
            Object ks = keySet.invoke(map);
            if (ks != null) {
                try {
                    Method toLongArray = ks.getClass().getMethod("toLongArray");
                    Object arr = toLongArray.invoke(ks);
                    if (arr instanceof long[]) return (long[]) arr;
                } catch (NoSuchMethodException ignored) {
                }

                List<Long> longs = new ArrayList<>();
                if (ks instanceof Iterable<?> it) {
                    for (Object o : it) {
                        if (o instanceof Long l) longs.add(l);
                    }
                }
                long[] out = new long[longs.size()];
                for (int i = 0; i < longs.size(); i++) out[i] = longs.get(i);
                return out;
            }

            return new long[0];
        } catch (Throwable ignored) {
            return new long[0];
        }
    }
}
