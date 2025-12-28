package com.tellik.crookedcraft.brewing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Datapack-driven thermal transforms.
 *
 * Unified model supports:
 * - Cooling transforms (at_or_below_temp_c)  -> Mode.COOLING
 * - Heating transforms (at_or_above_temp_c)  -> Mode.HEATING
 * - Optional level gating (required_level / min_level / max_level)
 * - Optional solid gating (required_solid) and consumption (clear_solid / consume_solid)
 * - Optional level output (set_level) or preserve_level behavior
 * - Optional reset_brew (clears ingredients/recipe/thermals via VesselState.clearAll())
 *
 * Solids:
 * - Stored primarily in BrewVesselBlockEntity for correct client rendering/sync.
 * - VesselState reflection is a fallback only (safe during refactors).
 */
public final class ThermalTransformManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record DropDef(ResourceLocation itemId, int count) {}

    /** Legacy record kept for older status paths (still useful). */
    public record CoolingTransform(
            float atOrBelowTempC,
            ResourceLocation setBlockId,
            DropDef drop,
            boolean untrack,
            Integer requiredLevel,
            Integer minLevel,
            Integer maxLevel
    ) {}

    public enum Mode {
        COOLING,
        HEATING
    }

    public record ThermalTransform(
            Mode mode,
            float thresholdC,
            ResourceLocation setBlockId,
            DropDef drop,
            boolean untrack,
            Integer requiredLevel,
            Integer minLevel,
            Integer maxLevel,
            ResourceLocation requiresSolidId, // nullable
            boolean consumeSolid,
            Integer setLevel,                // nullable
            boolean preserveLevel,
            boolean resetBrew
    ) {}

    private static volatile Map<Block, List<ThermalTransform>> transformsByBlock = Collections.emptyMap();

    private ThermalTransformManager() {}

    // -------------------------------------------------------------------------
    // Public API: loading / queries
    // -------------------------------------------------------------------------

    public static void applyTransforms(Map<ResourceLocation, List<ThermalTransform>> defs) {
        Map<Block, List<ThermalTransform>> resolved = new HashMap<>();

        if (defs == null || defs.isEmpty()) {
            transformsByBlock = Collections.emptyMap();
            LOGGER.warn("[crookedcraft] Loaded thermal transforms: 0 source block(s). (defs empty)");
            return;
        }

        for (Map.Entry<ResourceLocation, List<ThermalTransform>> e : defs.entrySet()) {
            ResourceLocation fromId = e.getKey();
            Block fromBlock = ForgeRegistries.BLOCKS.getValue(fromId);
            if (fromBlock == null) {
                LOGGER.warn("[crookedcraft] Unknown transform source block '{}' - skipping.", fromId);
                continue;
            }

            List<ThermalTransform> list = e.getValue();
            if (list == null || list.isEmpty()) continue;

            List<ThermalTransform> kept = new ArrayList<>(list.size());
            for (ThermalTransform t : list) {
                if (t == null) continue;

                Block setBlock = ForgeRegistries.BLOCKS.getValue(t.setBlockId());
                if (setBlock == null) {
                    LOGGER.warn("[crookedcraft] Unknown set_block '{}' for '{}' - skipping transform.", t.setBlockId(), fromId);
                    continue;
                }

                if (!(t.thresholdC() > -100000f)) {
                    LOGGER.warn("[crookedcraft] Invalid threshold for '{}' - skipping transform.", fromId);
                    continue;
                }

                if (t.drop != null) {
                    Item dropItem = ForgeRegistries.ITEMS.getValue(t.drop.itemId());
                    if (dropItem == null) {
                        LOGGER.warn("[crookedcraft] Unknown drop item '{}' for '{}' (drop will be skipped at runtime).",
                                t.drop.itemId(), fromId);
                    }
                }

                if (t.requiresSolidId != null) {
                    Block solidBlock = ForgeRegistries.BLOCKS.getValue(t.requiresSolidId);
                    if (solidBlock == null) {
                        LOGGER.warn("[crookedcraft] Unknown required_solid '{}' for '{}' - skipping transform.",
                                t.requiresSolidId, fromId);
                        continue;
                    }
                }

                kept.add(t);
            }

            if (!kept.isEmpty()) {
                resolved.put(fromBlock, Collections.unmodifiableList(kept));
            }
        }

        transformsByBlock = Collections.unmodifiableMap(resolved);

        LOGGER.info("[crookedcraft] Loaded thermal transforms: {} source block(s).", transformsByBlock.size());
        if (!transformsByBlock.isEmpty()) {
            LOGGER.info("[crookedcraft] Thermal transform sources:");
            for (Block b : transformsByBlock.keySet()) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
                LOGGER.info("[crookedcraft]  - {} ({} transform(s))", id, transformsByBlock.get(b).size());
            }
        }
    }

    /** Used by status formatting / debugging. */
    public static List<ThermalTransform> getTransformsFor(Block block) {
        List<ThermalTransform> list = transformsByBlock.get(block);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Used by right-click logic to decide whether a BlockItem should be treated as a "solid insert"
     * for this vessel, instead of falling through to ingredient logic.
     */
    public static boolean isSolidRelevantFor(Block vesselBlock, ResourceLocation solidId) {
        if (vesselBlock == null || solidId == null) return false;

        List<ThermalTransform> list = transformsByBlock.get(vesselBlock);
        if (list == null || list.isEmpty()) return false;

        for (ThermalTransform t : list) {
            if (t.requiresSolidId != null && t.requiresSolidId.equals(solidId)) return true;
        }
        return false;
    }

    /**
     * General insertion gate for solids: must be a valid, non-air block.
     * (More specific gating happens in BrewingForgeEvents.)
     */
    public static boolean canInsertSolid(ServerLevel level, BlockPos pos, BlockState state, ResourceLocation solidBlockId) {
        if (solidBlockId == null) return false;
        Block b = ForgeRegistries.BLOCKS.getValue(solidBlockId);
        if (b == null) return false;
        return !b.defaultBlockState().isAir();
    }

    /**
     * Main transform application called from tick loop.
     * Returns true if a transform applied (and the tick loop should continue).
     */
    public static boolean tryApplyTransforms(ServerLevel level,
                                             BlockPos pos,
                                             BlockState state,
                                             BrewingVesselData.VesselState v,
                                             Iterator<Map.Entry<Long, BrewingVesselData.VesselState>> dataIterator,
                                             BrewingVesselData data) {

        List<ThermalTransform> list = transformsByBlock.get(state.getBlock());
        if (list == null || list.isEmpty()) return false;

        float tempC = (v != null && !Float.isNaN(v.tempC)) ? v.tempC : Float.NaN;
        int lvl = getCauldronLevel(state);
        ResourceLocation insertedSolid = getInsertedSolidId(level, pos, v);

        for (ThermalTransform t : list) {

            // Threshold
            if (t.mode() == Mode.COOLING) {
                if (Float.isNaN(tempC) || tempC > t.thresholdC()) continue;
            } else {
                if (Float.isNaN(tempC) || tempC < t.thresholdC()) continue;
            }

            // Level gating
            if (t.requiredLevel != null && lvl != t.requiredLevel.intValue()) continue;
            if (t.minLevel != null && lvl < t.minLevel.intValue()) continue;
            if (t.maxLevel != null && lvl > t.maxLevel.intValue()) continue;

            // Solid gating
            if (t.requiresSolidId != null) {
                if (insertedSolid == null) continue;
                if (!t.requiresSolidId.equals(insertedSolid)) continue;
            }

            // Resolve output block
            Block setBlock = ForgeRegistries.BLOCKS.getValue(t.setBlockId());
            if (setBlock == null) continue;

            boolean blockChanges = (state.getBlock() != setBlock);

            // If the block changes OR the transform says to consume/clear OR we reset brew,
            // clear whatever solid is currently stored.
            boolean shouldClearSolid = t.consumeSolid || blockChanges || t.resetBrew;

            // Apply new blockstate
            BlockState out = setBlock.defaultBlockState();
            out = applyOutputLevel(out, state, t.setLevel, t.preserveLevel);

            level.setBlock(pos, out, 3);
            level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos);

            // Clear solid (BE + fallback state) before we possibly store a new one from "drop"
            if (shouldClearSolid) {
                clearInsertedSolidId(level, pos, v);
            }

            // reset_brew: wipe ingredients/recipe/thermals so it behaves like a fresh vessel
            if (t.resetBrew && v != null) {
                v.clearAll();
            }

            // DROP BEHAVIOR CHANGE:
            // If drop item is a BlockItem (ice/obsidian/etc), store it as the vessel solid instead of dropping.
            if (t.drop != null) {
                int count = Math.max(1, t.drop.count());

                boolean storedAsSolid = tryStoreDropAsSolid(level, pos, v, t.drop.itemId(), count);

                if (!storedAsSolid) {
                    // Original behavior for non-block items
                    Item dropItem = ForgeRegistries.ITEMS.getValue(t.drop.itemId());
                    if (dropItem != null) {
                        ItemStack dropStack = new ItemStack(dropItem, count);
                        Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, dropStack);
                    }
                }
            }

            // Untrack if requested
            if (t.untrack) {
                dataIterator.remove();
            }

            data.setDirty();
            return true;
        }

        return false;
    }


    // -------------------------------------------------------------------------
    // Back-compat wrapper (still handy)
    // -------------------------------------------------------------------------

    public static void applyCoolingTransforms(Map<ResourceLocation, CoolingTransform> defs) {
        Map<ResourceLocation, List<ThermalTransform>> out = new HashMap<>();

        for (Map.Entry<ResourceLocation, CoolingTransform> e : defs.entrySet()) {
            CoolingTransform c = e.getValue();
            if (c == null) continue;

            ThermalTransform t = new ThermalTransform(
                    Mode.COOLING,
                    c.atOrBelowTempC(),
                    c.setBlockId(),
                    c.drop(),
                    c.untrack(),
                    c.requiredLevel(),
                    c.minLevel(),
                    c.maxLevel(),
                    null,
                    false,
                    null,
                    true,
                    false
            );

            out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(t);
        }

        applyTransforms(out);
    }

    public static CoolingTransform getCoolingTransformFor(Block block) {
        List<ThermalTransform> list = transformsByBlock.get(block);
        if (list == null) return null;

        for (ThermalTransform t : list) {
            if (t.mode() == Mode.COOLING) {
                return new CoolingTransform(
                        t.thresholdC(),
                        t.setBlockId(),
                        t.drop(),
                        t.untrack(),
                        t.requiredLevel(),
                        t.minLevel(),
                        t.maxLevel()
                );
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static int getCauldronLevel(BlockState state) {
        if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        if (state.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) {
            return state.getValue(BlockStateProperties.LEVEL_CAULDRON);
        }
        return 0;
    }

    private static BlockState applyOutputLevel(BlockState out, BlockState src, Integer setLevel, boolean preserveLevel) {
        Integer srcLvl = null;
        if (src.hasProperty(LayeredCauldronBlock.LEVEL)) srcLvl = src.getValue(LayeredCauldronBlock.LEVEL);
        else if (src.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) srcLvl = src.getValue(BlockStateProperties.LEVEL_CAULDRON);

        if (setLevel != null) {
            int clamped = Math.max(1, Math.min(3, setLevel.intValue()));
            if (out.hasProperty(LayeredCauldronBlock.LEVEL)) return out.setValue(LayeredCauldronBlock.LEVEL, clamped);
            if (out.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) return out.setValue(BlockStateProperties.LEVEL_CAULDRON, clamped);
            return out;
        }

        if (preserveLevel && srcLvl != null) {
            if (out.hasProperty(LayeredCauldronBlock.LEVEL)) return out.setValue(LayeredCauldronBlock.LEVEL, srcLvl);
            if (out.hasProperty(BlockStateProperties.LEVEL_CAULDRON)) return out.setValue(BlockStateProperties.LEVEL_CAULDRON, srcLvl);
        }

        return out;
    }

    // -------------------------------------------------------------------------
    // Solid ID helpers (BlockEntity preferred; VesselState fallback via reflection)
    // -------------------------------------------------------------------------

    private static final String[] SOLID_METHOD_GETTERS = new String[] {
            "getSolidBlockId",
            "getInsertedSolidId",
            "getSolidId"
    };

    private static final String[] SOLID_METHOD_SETTERS = new String[] {
            "setSolidBlockId",
            "setInsertedSolidId",
            "setSolidId"
    };

    private static final String[] SOLID_FIELD_CANDIDATES = new String[] {
            "solidId",
            "solidBlockId",
            "solidItemId",
            "insertedSolidId",
            "insertedSolid",
            "solid"
    };

    private static ResourceLocation getInsertedSolidId(ServerLevel level, BlockPos pos, BrewingVesselData.VesselState v) {
        ResourceLocation beVal = getSolidFromBlockEntity(level, pos);
        if (beVal != null) return beVal;
        return getSolidFromVesselState(v);
    }

    private static void clearInsertedSolidId(ServerLevel level, BlockPos pos, BrewingVesselData.VesselState v) {
        setSolidOnBlockEntity(level, pos, null);

        if (v != null) {
            for (String name : SOLID_FIELD_CANDIDATES) {
                Field f = tryFindField(v.getClass(), name);
                if (f == null) continue;
                try {
                    f.setAccessible(true);
                    if (f.getType() == ResourceLocation.class) f.set(v, null);
                    else if (f.getType() == String.class) f.set(v, null);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static ResourceLocation getSolidFromBlockEntity(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;

        if (be instanceof com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity b) {
            try {
                return b.getSolidBlockId();
            } catch (Throwable ignored) {}
        }

        for (String m : SOLID_METHOD_GETTERS) {
            try {
                Method mm = be.getClass().getMethod(m);
                Object val = mm.invoke(be);
                if (val instanceof ResourceLocation rl) return rl;
                if (val instanceof String s) {
                    ResourceLocation rl = ResourceLocation.tryParse(s);
                    if (rl != null) return rl;
                }
            } catch (Throwable ignored) {}
        }

        for (String fName : SOLID_FIELD_CANDIDATES) {
            try {
                Field f = tryFindField(be.getClass(), fName);
                if (f == null) continue;
                f.setAccessible(true);
                Object val = f.get(be);
                if (val instanceof ResourceLocation rl) return rl;
                if (val instanceof String s) {
                    ResourceLocation rl = ResourceLocation.tryParse(s);
                    if (rl != null) return rl;
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private static void setSolidOnBlockEntity(ServerLevel level, BlockPos pos, ResourceLocation id) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;

        if (be instanceof com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity b) {
            try {
                b.setSolidBlockId(id);
                b.setChanged();
                BlockState st = level.getBlockState(pos);
                level.sendBlockUpdated(pos, st, st, 3);
                return;
            } catch (Throwable ignored) {}
        }

        for (String m : SOLID_METHOD_SETTERS) {
            try {
                try {
                    Method mm = be.getClass().getMethod(m, ResourceLocation.class);
                    mm.invoke(be, id);
                    be.setChanged();
                    BlockState st = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, st, st, 3);
                    return;
                } catch (NoSuchMethodException ignored) {}

                try {
                    Method mm = be.getClass().getMethod(m, String.class);
                    mm.invoke(be, id == null ? null : id.toString());
                    be.setChanged();
                    BlockState st = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, st, st, 3);
                    return;
                } catch (NoSuchMethodException ignored) {}
            } catch (Throwable ignored) {}
        }

        for (String fName : SOLID_FIELD_CANDIDATES) {
            try {
                Field f = tryFindField(be.getClass(), fName);
                if (f == null) continue;
                f.setAccessible(true);

                if (f.getType() == ResourceLocation.class) {
                    f.set(be, id);
                    be.setChanged();
                    BlockState st = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, st, st, 3);
                    return;
                }
                if (f.getType() == String.class) {
                    f.set(be, id == null ? null : id.toString());
                    be.setChanged();
                    BlockState st = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, st, st, 3);
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static ResourceLocation getSolidFromVesselState(BrewingVesselData.VesselState v) {
        if (v == null) return null;

        for (String name : SOLID_FIELD_CANDIDATES) {
            Field f = tryFindField(v.getClass(), name);
            if (f == null) continue;

            try {
                f.setAccessible(true);
                Object val = f.get(v);
                if (val == null) continue;

                if (val instanceof ResourceLocation rl) return rl;
                if (val instanceof String s) {
                    ResourceLocation rl = ResourceLocation.tryParse(s);
                    if (rl != null) return rl;
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private static Field tryFindField(Class<?> cls, String fieldName) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean tryStoreDropAsSolid(ServerLevel level,
                                               BlockPos pos,
                                               BrewingVesselData.VesselState v,
                                               ResourceLocation dropItemId,
                                               int count) {

        if (dropItemId == null) return false;

        Item dropItem = ForgeRegistries.ITEMS.getValue(dropItemId);
        if (!(dropItem instanceof BlockItem bi)) return false;

        Block b = bi.getBlock();
        if (b == null) return false;

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(b);
        if (blockId == null) return false;

        // We store EXACTLY one solid in the vessel.
        // If count > 1, we store one and drop the rest to avoid silent loss.
        setSolidOnBlockEntity(level, pos, blockId);

        // Keep VesselState fallback in sync too (helps status/other refactors)
        if (v != null) {
            for (String name : SOLID_FIELD_CANDIDATES) {
                Field f = tryFindField(v.getClass(), name);
                if (f == null) continue;
                try {
                    f.setAccessible(true);
                    if (f.getType() == ResourceLocation.class) {
                        f.set(v, blockId);
                        break;
                    } else if (f.getType() == String.class) {
                        f.set(v, blockId.toString());
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        int remainder = Math.max(0, count - 1);
        if (remainder > 0) {
            ItemStack extra = new ItemStack(dropItem, remainder);
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, extra);
        }

        return true;
    }

}
