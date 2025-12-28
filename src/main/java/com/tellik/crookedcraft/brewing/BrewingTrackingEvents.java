package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "crookedcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BrewingTrackingEvents {

    private BrewingTrackingEvents() {}

    /**
     * Track newly placed brew vessels immediately.
     * This guarantees that later fills (water/lava/snow) still have a tracked VesselState and will tick.
     */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        BlockState placed = event.getPlacedBlock();
        if (!placed.is(ModTags.Blocks.BREW_VESSELS)) return;

        BrewingVesselData data = BrewingVesselData.get(serverLevel);
        long key = event.getPos().asLong();

        data.ensureTracked(key);
        data.setDirty();
    }

    /**
     * Track brew vessels when their chunk loads.
     * This restores transforms after restart / relog / chunk unload-reload cycles.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        // Fast path: only look at existing block entities; do NOT scan the whole chunk volume.
        BrewingVesselData data = BrewingVesselData.get(serverLevel);

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof BrewVesselBlockEntity)) continue;

            long key = be.getBlockPos().asLong();
            data.ensureTracked(key);
        }

        // Mark dirty once if we actually tracked something new (optional).
        // If ensureTracked is idempotent and cheap, this is fine as-is.
        data.setDirty();
    }
}
