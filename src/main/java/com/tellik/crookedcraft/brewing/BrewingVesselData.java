package com.tellik.crookedcraft.brewing;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Server-authoritative saved state for "brew-tracked" cauldrons.
 *
 * Milestone 1 stores:
 * - which positions are tracked
 * - boil progress
 * - current required boil ticks (based on heat source)
 * - whether currently boiling
 *
 * NOTE (Forge/MC 1.20.1):
 * Use DimensionDataStorage#computeIfAbsent(loader, creator, name)
 * (NOT SavedData.Factory, which does not exist here).
 */
public final class BrewingVesselData extends SavedData {
    private static final String DATA_NAME = "crookedcraft_brewing_vessels";

    public static final class VesselState {
        public int boilProgress;
        public int boilTicksRequired;
        public boolean boiling;

        public VesselState() {}

        public VesselState(int boilProgress, int boilTicksRequired, boolean boiling) {
            this.boilProgress = boilProgress;
            this.boilTicksRequired = boilTicksRequired;
            this.boiling = boiling;
        }
    }

    private final Map<Long, VesselState> vessels = new HashMap<>();

    public BrewingVesselData() {}

    public static BrewingVesselData get(ServerLevel level) {
        // 1.20.1 signature:
        // computeIfAbsent(Function<CompoundTag, T> load, Supplier<T> create, String id)
        return level.getDataStorage().computeIfAbsent(
                BrewingVesselData::load,
                BrewingVesselData::new,
                DATA_NAME
        );
    }

    public boolean isTracked(long posLong) {
        return vessels.containsKey(posLong);
    }

    public VesselState getState(long posLong) {
        return vessels.get(posLong);
    }

    public void track(long posLong) {
        vessels.putIfAbsent(posLong, new VesselState(0, 0, false));
        setDirty();
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
            CompoundTag v = new CompoundTag();
            v.putLong("pos", e.getKey());

            VesselState s = e.getValue();
            v.putInt("boilProgress", s.boilProgress);
            v.putInt("boilTicksRequired", s.boilTicksRequired);
            v.putBoolean("boiling", s.boiling);

            list.add(v);
        }
        tag.put("vessels", list);
        return tag;
    }

    private static BrewingVesselData load(CompoundTag tag) {
        BrewingVesselData data = new BrewingVesselData();
        ListTag list = tag.getList("vessels", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag v = list.getCompound(i);
            long pos = v.getLong("pos");

            VesselState s = new VesselState(
                    v.getInt("boilProgress"),
                    v.getInt("boilTicksRequired"),
                    v.getBoolean("boiling")
            );

            data.vessels.put(pos, s);
        }
        return data;
    }
}
