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

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class VesselSnapshot {
    public final ServerLevel level;
    public final BlockPos pos;
    public final BlockState state;

    // What block is it?
    public final BrewCauldronType cauldronType;

    // Fluid
    public final BrewFluidType filledFluid;
    public final boolean hasFluid;
    public final int fluidLevel; // 0..3 where applicable

    // Heat
    public final boolean heatPresent;
    public final int heatStrength; // best-effort; may be 0/1 if unknown

    // Existing runtime state (from BrewingVesselData)
    public final boolean boiling;
    public final int boilProgress;
    public final int boilTicksRequired;

    public final boolean mixProgress;
    public final boolean brewProgress;

    // "Servings" is not guaranteed to exist in your VesselState yet.
    // We best-effort map it (or map brew levels) and fall back to 0.
    public final int brewServings;
    public final boolean hasBrew;

    public final boolean doomed;
    @Nullable public final String matchedRecipeId;
    public final int ingredientCount;

    // Capability flags (derived)
    public final boolean canBoil;
    public final boolean canMelt;
    public final boolean canCool;

    private VesselSnapshot(ServerLevel level,
                           BlockPos pos,
                           BlockState state,
                           BrewCauldronType cauldronType,
                           BrewFluidType filledFluid,
                           boolean hasFluid,
                           int fluidLevel,
                           boolean heatPresent,
                           int heatStrength,
                           boolean boiling,
                           int boilProgress,
                           int boilTicksRequired,
                           boolean mixProgress,
                           boolean brewProgress,
                           int brewServings,
                           boolean hasBrew,
                           boolean doomed,
                           @Nullable String matchedRecipeId,
                           int ingredientCount,
                           boolean canBoil,
                           boolean canMelt,
                           boolean canCool) {

        this.level = level;
        this.pos = pos;
        this.state = state;

        this.cauldronType = cauldronType;

        this.filledFluid = filledFluid;
        this.hasFluid = hasFluid;
        this.fluidLevel = fluidLevel;

        this.heatPresent = heatPresent;
        this.heatStrength = heatStrength;

        this.boiling = boiling;
        this.boilProgress = boilProgress;
        this.boilTicksRequired = boilTicksRequired;

        this.mixProgress = mixProgress;
        this.brewProgress = brewProgress;
        this.brewServings = brewServings;
        this.hasBrew = hasBrew;

        this.doomed = doomed;
        this.matchedRecipeId = matchedRecipeId;
        this.ingredientCount = ingredientCount;

        this.canBoil = canBoil;
        this.canMelt = canMelt;
        this.canCool = canCool;
    }

    public static VesselSnapshot capture(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // 1) Identify cauldron type + fluid type
        BrewCauldronType cauldronType = detectCauldronType(state);
        BrewFluidType fluidType = detectFluidType(cauldronType);

        int lvl = 0;
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            lvl = state.getValue(LayeredCauldronBlock.LEVEL);
        }

        boolean hasFluid = (fluidType != BrewFluidType.NONE);

        // 2) Heat detection (authoritative, compile-safe)
        // Your code already uses HeatSourceManager.getBoilTicksFor(sl, pos)
        // to decide whether boiling is possible and how long it takes.
        HeatSourceManager.HeatProfile heatProfile = HeatSourceManager.getHeatProfile(level, pos);
        boolean heatPresent = (heatProfile != null);

        // boil tick legacy fields are unused under thermal-only.
        // keep as 0 for snapshot/debug compatibility.
        int boilTicksRequired = 0;


        // Heat strength is optional (best-effort) and should never break compilation.
        // If we can’t read a strength, we use 1 when heat is present, else 0.
        int heatStrength = heatPresent ? 1 : 0;
        int reflectedStrength = tryReflectHeatStrength(level, pos);
        if (reflectedStrength >= 0) {
            heatStrength = reflectedStrength;
        }

        // 3) Runtime vessel state (may be absent/untracked)
        BrewingVesselData data = BrewingVesselData.get(level);
        BrewingVesselData.VesselState v = data.getTrackedState(pos.asLong());

        boolean boiling = false;
        int boilProgress = 0;

        boolean mixProgress = false;
        boolean brewProgress = false;

        int brewServings = 0;
        boolean hasBrew = false;

        boolean doomed = false;
        String matchedRecipeId = null;
        int ingredientCount = 0;

        if (v != null) {
            // These fields DO exist in your project based on earlier code paths.
            boiling = v.boiling;
            boilProgress = v.boilProgress;

            // matchedRecipeId is a ResourceLocation in your project; stringify safely.
            if (v.matchedRecipeId != null) {
                matchedRecipeId = v.matchedRecipeId.toString();
            }

            doomed = v.doomed;

            // Ingredients map exists in your project.
            ingredientCount = (v.ingredients != null) ? v.ingredients.size() : 0;
            mixProgress = ingredientCount > 0;

            // Brewing in milestone-2 terms: if a recipe candidate is locked in / matched.
            brewProgress = (v.matchedRecipeId != null);

            // Servings/levels: your VesselState doesn't have brewServings yet.
            // So we best-effort read an int from likely fields, else 0.
            brewServings = readIntBestEffort(v,
                    "brewServings",
                    "servings",
                    "brewLevels",
                    "completedBrewLevels",
                    "completedLevels",
                    "potionLevels",
                    "completedPotionLevels"
            );

            hasBrew = brewServings > 0;
        }

        // 4) Capability flags (conservative: do not change behavior yet)
        boolean canBoil = hasFluid && heatPresent;
        boolean canMelt = (fluidType == BrewFluidType.POWDER_SNOW) && heatPresent;
        boolean canCool = hasFluid && !heatPresent;

        return new VesselSnapshot(
                level, pos, state,
                cauldronType,
                fluidType,
                hasFluid,
                lvl,
                heatPresent,
                heatStrength,
                boiling,
                boilProgress,
                boilTicksRequired,
                mixProgress,
                brewProgress,
                brewServings,
                hasBrew,
                doomed,
                matchedRecipeId,
                ingredientCount,
                canBoil,
                canMelt,
                canCool
        );
    }

    private static BrewCauldronType detectCauldronType(BlockState state) {
        if (state.getBlock() instanceof BrewWaterCauldronBlock) return BrewCauldronType.BREW_WATER;
        if (state.getBlock() instanceof BrewLavaCauldronBlock) return BrewCauldronType.BREW_LAVA;
        if (state.getBlock() instanceof BrewPowderSnowCauldronBlock) return BrewCauldronType.BREW_POWDER_SNOW;
        if (state.getBlock() instanceof BrewCauldronBlock) return BrewCauldronType.BREW_EMPTY;
        return BrewCauldronType.OTHER;
    }

    private static BrewFluidType detectFluidType(BrewCauldronType t) {
        switch (t) {
            case BREW_WATER: return BrewFluidType.WATER;
            case BREW_LAVA: return BrewFluidType.LAVA;
            case BREW_POWDER_SNOW: return BrewFluidType.POWDER_SNOW;
            default: return BrewFluidType.NONE;
        }
    }

    /**
     * Best-effort reflection:
     * - If you later expose HeatSourceManager.getHeatUnder(...) or similar again,
     *   this will start reporting the real strength automatically.
     * - If not available, returns -1 and we fall back to 0/1.
     */
    private static int tryReflectHeatStrength(ServerLevel level, BlockPos pos) {
        try {
            // Look for a static method on HeatSourceManager that sounds like "getHeatUnder"
            // Signature guesses: (ServerLevel, BlockPos) or (Level, BlockPos)
            Method m = null;
            for (Method candidate : HeatSourceManager.class.getDeclaredMethods()) {
                if (!candidate.getName().equals("getHeatUnder")) continue;
                Class<?>[] p = candidate.getParameterTypes();
                if (p.length == 2 && p[1] == BlockPos.class) {
                    m = candidate;
                    m.setAccessible(true);
                    break;
                }
            }
            if (m == null) return -1;

            Object heatInfo = m.invoke(null, level, pos);
            if (heatInfo == null) return 0;

            // Try common fields: value, strength, heat
            Integer val =
                    readIntField(heatInfo, "value");
            if (val == null) val = readIntField(heatInfo, "strength");
            if (val == null) val = readIntField(heatInfo, "heat");
            if (val != null) return val;

            // Try getters: getValue(), getStrength()
            Integer viaGetter = readIntGetter(heatInfo, "getValue");
            if (viaGetter == null) viaGetter = readIntGetter(heatInfo, "getStrength");
            if (viaGetter != null) return viaGetter;

            return 1; // heat exists but we can't read its strength
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int readIntBestEffort(Object obj, String... names) {
        for (String n : names) {
            Integer v = readIntField(obj, n);
            if (v != null) return v;
        }

        // Fallback: scan for any int field that contains "brew" or "serv" or "level"
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                String name = f.getName().toLowerCase();
                if (!(name.contains("brew") || name.contains("serv") || name.contains("level"))) continue;
                f.setAccessible(true);
                return f.getInt(obj);
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    private static Integer readIntField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            if (f.getType() != int.class) return null;
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer readIntGetter(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            if (m.getReturnType() != int.class) return null;
            return (Integer) m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
