package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.CrookedCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Brewing-related tags (datapack-defined).
 */
public final class ModTags {
    private ModTags() {}

    public static final class Blocks {
        public static final TagKey<Block> BREW_VESSELS =
                TagKey.create(
                        Registries.BLOCK,
                        ResourceLocation.fromNamespaceAndPath(CrookedCraft.MODID, "brew_vessels")
                );

        private Blocks() {}
    }
}
