package com.tellik.crookedcraft.brewing.engine;

import com.tellik.crookedcraft.brewing.BrewingVesselData;
import com.tellik.crookedcraft.brewing.ModTags;
import com.tellik.crookedcraft.brewing.ThermalTransformManager;
import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

public final class BrewStatusFormatter {

    // Keep these aligned with BrewingForgeEvents defaults (or centralize later).
    private static final float AMBIENT_TEMP_C = 12.0f;
    private static final float SNOW_BASE_TEMP_C = -10.0f;
    private static final float LAVA_TEMP_C = 1000.0f;

    private BrewStatusFormatter() {}

    public static void sendStatus(net.minecraft.world.entity.player.Player player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (!state.is(ModTags.Blocks.BREW_VESSELS)) {
            player.displayClientMessage(Component.literal("Not a brew vessel."), false);
            return;
        }

        BrewingVesselData data = BrewingVesselData.get(level);
        BrewingVesselData.VesselState v = data.getStateIfTracked(pos.asLong());

        int cauldronLevel = getCauldronLevel(state);

        StringBuilder sb = new StringBuilder();
        sb.append("=== Brew Vessel Status ===\n");

        // Block name/id
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        sb.append("Block: ").append(blockId != null ? blockId : state.getBlock().toString()).append("\n");

        // Level
        if (cauldronLevel > 0) sb.append("Level: ").append(cauldronLevel).append("\n");
        else sb.append("Level: (none)\n");

        // Tracking + temp
        if (v == null) {
            sb.append("Temp: (untracked)\n");
            sb.append("Boiling: (untracked)\n");
        } else {
            float temp = v.tempC;
            if (Float.isNaN(temp)) {
                temp = inferDefaultTemp(state);
                sb.append("Temp: (init) ").append(fmt1(temp)).append("C\n");
            } else {
                sb.append("Temp: ").append(fmt1(temp)).append("C\n");
            }

            sb.append("Boiling: ").append(v.boiling).append("\n");
            sb.append("Doomed: ").append(v.doomed).append("\n");
            sb.append("Matched Recipe: ").append(v.matchedRecipeId != null ? v.matchedRecipeId : "(none)").append("\n");
        }

        // Inserted solid (renderer source of truth is BE)
        ResourceLocation insertedSolid = getInsertedSolidFromBlockEntity(level, pos);
        sb.append("Inserted Solid: ").append(insertedSolid != null ? insertedSolid : "(none)").append("\n");

        // Transforms for this block
        List<ThermalTransformManager.ThermalTransform> transforms =
                ThermalTransformManager.getTransformsFor(state.getBlock());

        if (transforms.isEmpty()) {
            sb.append("Thermal Transforms: (none)\n");
        } else {
            sb.append("Thermal Transforms: ").append(transforms.size()).append("\n");

            float tempNow = (v != null && !Float.isNaN(v.tempC)) ? v.tempC : inferDefaultTemp(state);

            int idx = 1;
            for (ThermalTransformManager.ThermalTransform t : transforms) {
                boolean wouldApply = wouldApplyNow(t, tempNow, cauldronLevel, insertedSolid);

                sb.append("  ").append(idx++).append(") ");

                // Mode + threshold
                if (t.mode() == ThermalTransformManager.Mode.COOLING) {
                    sb.append("COOL <= ");
                } else {
                    sb.append("HEAT >= ");
                }
                sb.append(fmt1(t.thresholdC())).append("C");

                // Solid gating
                if (t.requiresSolidId() != null) {
                    sb.append(" | requires_solid=").append(t.requiresSolidId());
                    if (t.consumeSolid()) sb.append(" (consumes)");
                }

                // Level gating
                String lvlGate = formatLevelGate(t.requiredLevel(), t.minLevel(), t.maxLevel());
                if (!lvlGate.isEmpty()) {
                    sb.append(" | ").append(lvlGate);
                }

                // Output
                sb.append(" -> ").append(t.setBlockId());

                // Output level behavior
                if (t.setLevel() != null) {
                    sb.append(" (set_level=").append(t.setLevel()).append(")");
                } else if (t.preserveLevel()) {
                    sb.append(" (preserve_level)");
                }

                // Drop
                if (t.drop() != null) {
                    sb.append(" | drop=").append(t.drop().itemId()).append(" x").append(t.drop().count());
                }

                // Untrack
                if (t.untrack()) sb.append(" | untrack");

                sb.append(" | ").append(wouldApply ? "WOULD APPLY NOW" : "no");
                sb.append("\n");
            }
        }

        player.displayClientMessage(Component.literal(sb.toString()), false);
    }

    private static boolean wouldApplyNow(ThermalTransformManager.ThermalTransform t,
                                         float tempC,
                                         int cauldronLevel,
                                         ResourceLocation insertedSolid) {

        // Threshold
        if (t.mode() == ThermalTransformManager.Mode.COOLING) {
            if (tempC > t.thresholdC()) return false;
        } else {
            if (tempC < t.thresholdC()) return false;
        }

        // Level gating
        Integer req = t.requiredLevel();
        if (req != null && cauldronLevel != req.intValue()) return false;

        Integer min = t.minLevel();
        if (min != null && cauldronLevel < min.intValue()) return false;

        Integer max = t.maxLevel();
        if (max != null && cauldronLevel > max.intValue()) return false;

        // Solid gating
        ResourceLocation needs = t.requiresSolidId();
        if (needs != null) {
            if (insertedSolid == null) return false;
            if (!needs.equals(insertedSolid)) return false;
        }

        return true;
    }

    private static String formatLevelGate(Integer required, Integer min, Integer max) {
        StringBuilder sb = new StringBuilder();
        if (required != null) {
            sb.append("required_level=").append(required);
            return sb.toString();
        }
        boolean any = false;
        if (min != null) {
            sb.append("min_level=").append(min);
            any = true;
        }
        if (max != null) {
            if (any) sb.append(", ");
            sb.append("max_level=").append(max);
        }
        return sb.toString();
    }

    private static float inferDefaultTemp(BlockState state) {
        if (state.getBlock() instanceof BrewPowderSnowCauldronBlock) return SNOW_BASE_TEMP_C;
        if (state.getBlock() instanceof BrewLavaCauldronBlock) return LAVA_TEMP_C;
        return AMBIENT_TEMP_C;
    }

    private static ResourceLocation getInsertedSolidFromBlockEntity(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BrewVesselBlockEntity b) {
            return b.getSolidBlockId();
        }
        return null;
    }

    private static int getCauldronLevel(BlockState state) {
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        if (state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) {
            return state.getValue(BlockStateProperties.LEVEL_CAULDRON);
        }
        return 0;
    }

    private static String fmt1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
