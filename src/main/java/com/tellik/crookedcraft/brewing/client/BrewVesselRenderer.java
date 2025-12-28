package com.tellik.crookedcraft.brewing.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

public final class BrewVesselRenderer implements BlockEntityRenderer<BrewVesselBlockEntity> {

    public BrewVesselRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BrewVesselBlockEntity be,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource buffer,
                       int packedLight,
                       int packedOverlay) {

        ResourceLocation solidId = be.getSolidBlockId();
        if (solidId == null) return;

        Block block = ForgeRegistries.BLOCKS.getValue(solidId);
        if (block == null) return;

        BlockState solidState = block.defaultBlockState();

        // Render a miniature block “inside” the cauldron.
        // This is intentionally conservative: centered, slightly raised, scaled down.
        poseStack.pushPose();

        poseStack.translate(0.5, 0.25, 0.5);
        poseStack.scale(0.60f, 0.65f, 0.60f);
        poseStack.translate(-0.5, 0.0, -0.5);

        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                solidState,
                poseStack,
                buffer,
                packedLight,
                packedOverlay
        );

        poseStack.popPose();
    }
}
