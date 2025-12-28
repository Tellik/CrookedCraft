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
        // --- legacy fields you already have ---
        public int pendingFillTicks;
        public int boilProgress;
        public int boilTicksRequired;
        public boolean boiling;

        public boolean doomed;
        public ResourceLocation matchedRecipeId;
        public final Map<ResourceLocation, Integer> ingredients = new HashMap<>();

        // --- authoritative thermal state ---
        public float tempC = Float.NaN;
        public float lastTempC = Float.NaN;

        // --- NEW: single inserted solid block (optional) ---
        // Stored as a string for NBT stability and low coupling.
        // Example value: "minecraft:ice"
        public String insertedSolidId = null;

        /** Clear only thermals + legacy boil flags (kept inert). */
        public void clearThermals() {
            tempC = Float.NaN;
            lastTempC = Float.NaN;

            // legacy fields: keep zeroed so older code can't accidentally "revive" logic
            boilProgress = 0;
            boilTicksRequired = 0;
            boiling = false;
            pendingFillTicks = 0;
        }

        /** Clear brew state and inserted solid. */
        public void clearAll() {
            doomed = false;
            matchedRecipeId = null;
            ingredients.clear();

            insertedSolidId = null;

            clearThermals();
        }
    }

    private final Map<Long, VesselState> vessels = new HashMap<>();

    public BrewingVesselData() {}

    public static BrewingVesselData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(BrewingVesselData::load, BrewingVesselData::new, DATA_NAME);
    }

    /** Ensure a VesselState exists for posLong (and marks dirty if newly created). */
    public void ensureTracked(long posLong) {
        if (!vessels.containsKey(posLong)) {
            vessels.put(posLong, new VesselState());
            setDirty();
        }
    }

    /** Returns an existing VesselState (must be tracked already). Prefer calling ensureTracked(posLong) first. */
    public VesselState getTrackedState(long posLong) {
        return vessels.get(posLong);
    }

    /** Returns the VesselState if tracked, otherwise null. (Status UI helper) */
    public VesselState getStateIfTracked(long posLong) {
        return vessels.get(posLong);
    }

    public void untrack(long posLong) {
        if (vessels.remove(posLong) != null) {
            setDirty();
        }
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

            // legacy fields
            vtag.putInt("boilProgress", v.boilProgress);
            vtag.putInt("boilTicksRequired", v.boilTicksRequired);
            vtag.putBoolean("boiling", v.boiling);
            vtag.putInt("pendingFillTicks", v.pendingFillTicks);

            // brew state
            vtag.putBoolean("doomed", v.doomed);
            if (v.matchedRecipeId != null) {
                vtag.putString("matchedRecipeId", v.matchedRecipeId.toString());
            } else {
                vtag.remove("matchedRecipeId");
            }

            // ingredients
            ListTag ingList = new ListTag();
            for (Map.Entry<ResourceLocation, Integer> ing : v.ingredients.entrySet()) {
                CompoundTag it = new CompoundTag();
                it.putString("id", ing.getKey().toString());
                it.putInt("count", ing.getValue());
                ingList.add(it);
            }
            vtag.put("ingredients", ingList);

            // thermals (optional but recommended)
            if (!Float.isNaN(v.tempC)) vtag.putFloat("tempC", v.tempC);
            else vtag.remove("tempC");

            if (!Float.isNaN(v.lastTempC)) vtag.putFloat("lastTempC", v.lastTempC);
            else vtag.remove("lastTempC");

            // inserted solid
            if (v.insertedSolidId != null && !v.insertedSolidId.isEmpty()) {
                vtag.putString("insertedSolidId", v.insertedSolidId);
            } else {
                vtag.remove("insertedSolidId");
            }

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

            // legacy fields
            v.boilProgress = vtag.getInt("boilProgress");
            v.boilTicksRequired = vtag.getInt("boilTicksRequired");
            v.boiling = vtag.getBoolean("boiling");
            v.pendingFillTicks = vtag.getInt("pendingFillTicks");

            // brew state
            v.doomed = vtag.getBoolean("doomed");
            if (vtag.contains("matchedRecipeId")) {
                v.matchedRecipeId = ResourceLocation.tryParse(vtag.getString("matchedRecipeId"));
            }

            // ingredients
            ListTag ingList = vtag.getList("ingredients", Tag.TAG_COMPOUND);
            for (int j = 0; j < ingList.size(); j++) {
                CompoundTag it = ingList.getCompound(j);
                ResourceLocation iid = ResourceLocation.tryParse(it.getString("id"));
                int count = it.getInt("count");
                if (iid != null && count > 0) {
                    v.ingredients.put(iid, count);
                }
            }

            // thermals
            v.tempC = vtag.contains("tempC") ? vtag.getFloat("tempC") : Float.NaN;
            v.lastTempC = vtag.contains("lastTempC") ? vtag.getFloat("lastTempC") : Float.NaN;

            // inserted solid
            v.insertedSolidId = vtag.contains("insertedSolidId") ? vtag.getString("insertedSolidId") : null;
            if (v.insertedSolidId != null && v.insertedSolidId.isEmpty()) {
                v.insertedSolidId = null;
            }

            data.vessels.put(pos, v);
        }

        return data;
    }
}
