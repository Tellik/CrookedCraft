package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.CrookedCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    private ModTags() {}

    public static final class Blocks {
        public static final TagKey<Block> BREW_VESSELS =
                TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CrookedCraft.MODID, "brew_vessels"));

        private Blocks() {}
    }

    public static final class Items {
        /**
         * Items that can collect a completed brew from a cauldron.
         * v2 default: minecraft:glass_bottle (via datapack tag file)
         */
        public static final TagKey<Item> BREW_CONTAINERS =
                TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(CrookedCraft.MODID, "brew_containers"));

        private Items() {}
    }
}
