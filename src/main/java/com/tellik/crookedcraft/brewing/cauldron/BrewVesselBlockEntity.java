package com.tellik.crookedcraft.brewing.cauldron;

import com.tellik.crookedcraft.brewing.ModBrewingBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public final class BrewVesselBlockEntity extends BlockEntity {

    private static final String TAG_SOLID = "SolidBlockId";

    @Nullable
    private ResourceLocation solidBlockId;

    public BrewVesselBlockEntity(BlockPos pos, BlockState state) {
        super(ModBrewingBlockEntities.BREW_VESSEL.get(), pos, state);
    }

    @Nullable
    public ResourceLocation getSolidBlockId() {
        return solidBlockId;
    }

    public boolean hasSolid() {
        return solidBlockId != null;
    }

    /**
     * Set/clear the single solid held by this vessel.
     * SERVER-authoritative. Syncs to client via BE update packet.
     */
    public void setSolidBlockId(@Nullable ResourceLocation id) {
        if ((solidBlockId == null && id == null) || (solidBlockId != null && solidBlockId.equals(id))) {
            return;
        }

        solidBlockId = id;
        setChanged();

        // This triggers the BE update packet because we implement getUpdatePacket/getUpdateTag.
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    public void clearSolid() {
        setSolidBlockId(null);
    }

    // -------------------------------------------------------------------------
    // Save/load (world persistence)
    // -------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (solidBlockId != null) {
            tag.putString(TAG_SOLID, solidBlockId.toString());
        } else {
            // Keep world NBT clean; omit key when empty.
            tag.remove(TAG_SOLID);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        if (tag.contains(TAG_SOLID)) {
            String s = tag.getString(TAG_SOLID);
            if (s == null || s.isEmpty()) {
                solidBlockId = null;
            } else {
                solidBlockId = ResourceLocation.tryParse(s);
            }
        } else {
            solidBlockId = null;
        }
    }

    // -------------------------------------------------------------------------
    // Client sync (runtime updates)
    // -------------------------------------------------------------------------

    /**
     * IMPORTANT:
     * We always write TAG_SOLID into the update tag (even when empty) so the client
     * can reliably clear a previously-rendered solid.
     */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.putString(TAG_SOLID, solidBlockId == null ? "" : solidBlockId.toString());
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);

        // Ensure the client re-renders immediately when the value changes.
        if (level != null && level.isClientSide) {
            BlockState st = getBlockState();
            level.sendBlockUpdated(worldPosition, st, st, 3);
        }
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        } else {
            solidBlockId = null;
        }

        // Force rerender on client.
        if (level != null && level.isClientSide) {
            BlockState st = getBlockState();
            level.sendBlockUpdated(worldPosition, st, st, 3);
        }
    }
}
