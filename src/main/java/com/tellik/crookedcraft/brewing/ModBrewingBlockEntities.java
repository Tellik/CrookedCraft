package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.brewing.cauldron.BrewVesselBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBrewingBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "crookedcraft");

    public static final RegistryObject<BlockEntityType<BrewVesselBlockEntity>> BREW_VESSEL =
            BLOCK_ENTITIES.register("brew_vessel",
                    () -> BlockEntityType.Builder.of(
                            BrewVesselBlockEntity::new,
                            ModBrewingBlocks.BREW_CAULDRON.get(),
                            ModBrewingBlocks.BREW_WATER_CAULDRON.get(),
                            ModBrewingBlocks.BREW_LAVA_CAULDRON.get(),
                            ModBrewingBlocks.BREW_POWDER_SNOW_CAULDRON.get()
                    ).build(null));

    private ModBrewingBlockEntities() {}

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
