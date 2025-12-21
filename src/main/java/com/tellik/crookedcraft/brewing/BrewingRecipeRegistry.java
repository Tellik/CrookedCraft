package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.CrookedCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = CrookedCraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BrewingRecipeRegistry {
    private BrewingRecipeRegistry() {}

    public static final ResourceLocation CAULDRON_BREWING_ID =
            ResourceLocation.fromNamespaceAndPath(CrookedCraft.MODID, "cauldron_brewing");

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        // Recipe serializer (controls JSON "type": "crookedcraft:cauldron_brewing")
        event.register(ForgeRegistries.Keys.RECIPE_SERIALIZERS, helper ->
                helper.register(CAULDRON_BREWING_ID, CauldronBrewRecipeSerializer.INSTANCE)
        );

        // Recipe type (used for querying RecipeManager#getAllRecipesFor)
        event.register(Registries.RECIPE_TYPE, helper ->
                helper.register(CAULDRON_BREWING_ID, CauldronBrewRecipeType.INSTANCE)
        );
    }
}
