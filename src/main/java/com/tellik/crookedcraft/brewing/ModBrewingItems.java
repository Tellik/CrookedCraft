package com.tellik.crookedcraft.brewing;

import com.tellik.crookedcraft.CrookedCraft;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

/**
 * Brewing-specific items.
 *
 * We register through CrookedCraft.ITEMS (the mod's DeferredRegister).
 *
 * IMPORTANT: This class MUST be initialized at least once (call init()) or the static
 * RegistryObject registrations will never run.
 */
public final class ModBrewingItems {
    private ModBrewingItems() {}

    /** Call once during mod construction to force class initialization. */
    public static void init() {
        // no-op: forces static fields to initialize
    }

    public static final RegistryObject<Item> BLACK_SLUDGE =
            CrookedCraft.ITEMS.register("black_sludge", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> DOOMED_SLUDGE =
            CrookedCraft.ITEMS.register("doomed_sludge", () -> new Item(new Item.Properties()));
}
