package com.tellik.crookedcraft;

import com.mojang.logging.LogUtils;
import com.tellik.crookedcraft.brewing.ModBrewingBlocks;
import com.tellik.crookedcraft.brewing.ModBrewingItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(CrookedCraft.MODID)
public class CrookedCraft {

    public static final String MODID = "crookedcraft";
    private static final Logger LOGGER = LogUtils.getLogger();

    // Base registers (kept for future general content)
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);

    public static final DeferredRegister<net.minecraft.world.item.Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<CreativeModeTab> CROOKEDCRAFT_TAB =
            CREATIVE_MODE_TABS.register("crookedcraft", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.crookedcraft"))
                    .icon(() -> new ItemStack(ModBrewingBlocks.BREW_CAULDRON_ITEM.get()))
                    .displayItems((params, output) -> {
                        // Brewing (2.5.x)
                        output.accept(ModBrewingBlocks.BREW_CAULDRON_ITEM.get());
                        output.accept(ModBrewingItems.BLACK_SLUDGE.get());
                        output.accept(ModBrewingItems.DOOMED_SLUDGE.get());

                        // Add more later as you implement them (brew bottle, etc.)
                    })
                    .build()
            );

    public CrookedCraft(final FMLJavaModLoadingContext context) {
        final IEventBus modEventBus = context.getModEventBus();

        // Force-load brewing items so CrookedCraft.ITEMS.register(...) executes.
        // Without this, ModBrewingItems' static RegistryObject fields may never initialize.
        ModBrewingItems.init();

        // Module registers (register their blocks/items onto our DeferredRegisters)
        ModBrewingBlocks.register(modEventBus);

        // Lifecycle listeners
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::addCreative);

        // Register our DeferredRegisters
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // Forge bus registrations (only instance subscribers go here)
        MinecraftForge.EVENT_BUS.register(this);

        // IMPORTANT:
        // BrewingForgeEvents is annotated with:
        // @Mod.EventBusSubscriber(modid="crookedcraft", bus=FORGE)
        // so you must NOT manually register a new instance here (it can cause double event firing).
        // If you ever remove that annotation, THEN you would register it here.

        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[{}] Common setup", MODID);

        if (Config.logDirtBlock) {
            LOGGER.info("DIRT BLOCK >> {}", ForgeRegistries.BLOCKS.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.magicNumberIntroduction, Config.magicNumber);
        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    private void addCreative(final BuildCreativeModeTabContentsEvent event) {
        // Keep in a vanilla tab for convenience too:
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBrewingBlocks.BREW_CAULDRON_ITEM.get());

            // Handy for testing during 2.5.x; you can remove later if you want these only in your mod tab
            event.accept(ModBrewingItems.BLACK_SLUDGE.get());
            event.accept(ModBrewingItems.DOOMED_SLUDGE.get());
        }
    }

    @SubscribeEvent
    public void onServerStarting(final ServerStartingEvent event) {
        LOGGER.info("[{}] Server starting", MODID);
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(final FMLClientSetupEvent event) {
            LOGGER.info("[{}] Client setup", MODID);
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
