package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.CrookedCraft;
import com.tellik.crookedcraft.brewing.cauldron.BrewCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewLavaCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewPowderSnowCauldronBlock;
import com.tellik.crookedcraft.brewing.cauldron.BrewWaterCauldronBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBrewingBlocks {
    private ModBrewingBlocks() {}

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CrookedCraft.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CrookedCraft.MODID);

    // ---- Blocks ----

    public static final RegistryObject<Block> BREW_CAULDRON = BLOCKS.register(
            "brew_cauldron",
            () -> new BrewCauldronBlock(BlockBehaviour.Properties.copy(Blocks.CAULDRON))
    );

    /**
     * Internal state blocks (not normally obtainable as items).
     */
    public static final RegistryObject<Block> BREW_WATER_CAULDRON = BLOCKS.register(
            "brew_water_cauldron",
            () -> new BrewWaterCauldronBlock(BlockBehaviour.Properties.copy(Blocks.WATER_CAULDRON))
    );

    public static final RegistryObject<Block> BREW_LAVA_CAULDRON = BLOCKS.register(
            "brew_lava_cauldron",
            () -> new BrewLavaCauldronBlock(BlockBehaviour.Properties.copy(Blocks.LAVA_CAULDRON))
    );

    public static final RegistryObject<Block> BREW_POWDER_SNOW_CAULDRON = BLOCKS.register(
            "brew_powder_snow_cauldron",
            () -> new BrewPowderSnowCauldronBlock(BlockBehaviour.Properties.copy(Blocks.POWDER_SNOW_CAULDRON))
    );

    // ---- Items ----

    public static final RegistryObject<Item> BREW_CAULDRON_ITEM = ITEMS.register(
            "brew_cauldron",
            () -> new BlockItem(BREW_CAULDRON.get(), new Item.Properties())
    );

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CrookedCraft.MODID, path);
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
