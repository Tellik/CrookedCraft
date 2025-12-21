package com.tellik.crookedcraft.brewing;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class BrewingVesselData extends SavedData {
    private static final String DATA_NAME = "crookedcraft_brewing_vessels";

    public static final class VesselState {
        public int boilProgress = 0;
        public int boilTicksRequired = 0;
        public boolean boiling = false;

        // Brewing state
        public boolean doomed = false;
        public ResourceLocation matchedRecipeId = null;

        // Ingredients actually added (item id -> count)
        public final Map<ResourceLocation, Integer> ingredients = new HashMap<>();

        /**
         * Legacy field: previously used for a "pending fill window".
         * Kept for NBT backward compatibility; currently unused by the shim approach.
         */
        public int pendingFillTicks = 0;

        public void clearAll() {
            boilProgress = 0;
            boilTicksRequired = 0;
            boiling = false;

            doomed = false;
            matchedRecipeId = null;

            ingredients.clear();
            pendingFillTicks = 0;
        }
    }

    private final Map<Long, VesselState> vessels = new HashMap<>();

    public BrewingVesselData() {}

    public static BrewingVesselData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(BrewingVesselData::load, BrewingVesselData::new, DATA_NAME);
    }

    /**
     * Ensure a vessel entry exists for this position (creates if absent).
     * Preferred API for call sites to avoid inverted boolean usage.
     */
    public void ensureTracked(long posLong) {
        if (vessels.putIfAbsent(posLong, new VesselState()) == null) {
            setDirty();
        }
    }

    public void untrack(long posLong) {
        if (vessels.remove(posLong) != null) {
            setDirty();
        }
    }

    /**
     * Preferred accessor for call sites that expect a state after ensureTracked().
     * If missing unexpectedly, creates one to preserve invariants.
     */
    public VesselState getTrackedState(long posLong) {
        VesselState v = vessels.get(posLong);
        if (v == null) {
            v = new VesselState();
            vessels.put(posLong, v);
            setDirty();
        }
        return v;
    }

    public Iterator<Map.Entry<Long, VesselState>> iterator() {
        return vessels.entrySet().iterator();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();

        for (Map.Entry<Long, VesselState> e : vessels.entrySet()) {
            CompoundTag vtag = new CompoundTag();
            vtag.putLong("pos", e.getKey());

            VesselState v = e.getValue();
            vtag.putInt("boilProgress", v.boilProgress);
            vtag.putInt("boilTicksRequired", v.boilTicksRequired);
            vtag.putBoolean("boiling", v.boiling);

            vtag.putBoolean("doomed", v.doomed);
            if (v.matchedRecipeId != null) {
                vtag.putString("matchedRecipeId", v.matchedRecipeId.toString());
            }

            vtag.putInt("pendingFillTicks", v.pendingFillTicks);

            ListTag ingList = new ListTag();
            for (Map.Entry<ResourceLocation, Integer> ing : v.ingredients.entrySet()) {
                CompoundTag it = new CompoundTag();
                it.putString("id", ing.getKey().toString());
                it.putInt("count", ing.getValue());
                ingList.add(it);
            }
            vtag.put("ingredients", ingList);

            list.add(vtag);
        }

        tag.put("vessels", list);
        return tag;
    }

    public static BrewingVesselData load(CompoundTag tag) {
        BrewingVesselData data = new BrewingVesselData();

        ListTag list = tag.getList("vessels", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag vtag = list.getCompound(i);
            long pos = vtag.getLong("pos");

            VesselState v = new VesselState();
            v.boilProgress = vtag.getInt("boilProgress");
            v.boilTicksRequired = vtag.getInt("boilTicksRequired");
            v.boiling = vtag.getBoolean("boiling");

            v.doomed = vtag.getBoolean("doomed");
            if (vtag.contains("matchedRecipeId")) {
                v.matchedRecipeId = ResourceLocation.tryParse(vtag.getString("matchedRecipeId"));
            }

            v.pendingFillTicks = vtag.getInt("pendingFillTicks");

            ListTag ingList = vtag.getList("ingredients", Tag.TAG_COMPOUND);
            for (int j = 0; j < ingList.size(); j++) {
                CompoundTag it = ingList.getCompound(j);
                ResourceLocation iid = ResourceLocation.tryParse(it.getString("id"));
                int count = it.getInt("count");
                if (iid != null && count > 0) {
                    v.ingredients.put(iid, count);
                }
            }

            data.vessels.put(pos, v);
        }

        return data;
    }
}
