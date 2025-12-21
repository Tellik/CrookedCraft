package com.tellik.crookedcraft.brewing;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class CauldronBrewRecipe implements Recipe<Container> {

    public static final class CountedIngredient {
        public final Ingredient ingredient;
        public final int count;

        public CountedIngredient(Ingredient ingredient, int count) {
            this.ingredient = ingredient;
            this.count = Math.max(1, count);
        }
    }

    private final ResourceLocation id;
    private final ResourceLocation liquid; // e.g., minecraft:water
    private final List<CountedIngredient> ingredients; // order independent
    private final Item resultItem;
    private final @Nullable ResourceLocation potionId; // optional

    public CauldronBrewRecipe(ResourceLocation id,
                              ResourceLocation liquid,
                              List<CountedIngredient> ingredients,
                              Item resultItem,
                              @Nullable ResourceLocation potionId) {
        this.id = id;
        this.liquid = liquid;
        this.ingredients = List.copyOf(ingredients);
        this.resultItem = resultItem;
        this.potionId = potionId;
    }

    public ResourceLocation getLiquid() {
        return liquid;
    }

    public List<CountedIngredient> getCountedIngredients() {
        return ingredients;
    }

    public Item getResultItemItem() {
        return resultItem;
    }

    public @Nullable ResourceLocation getPotionId() {
        return potionId;
    }

    public ItemStack createResultStack() {
        ItemStack out = new ItemStack(resultItem);

        if (potionId != null) {
            Potion potion = ForgeRegistries.POTIONS.getValue(potionId);
            if (potion != null) {
                PotionUtils.setPotion(out, potion);
            }
        }

        return out;
    }

    public List<Ingredient> expandToSlots() {
        List<Ingredient> slots = new ArrayList<>();
        for (CountedIngredient ci : ingredients) {
            for (int i = 0; i < ci.count; i++) {
                slots.add(ci.ingredient);
            }
        }
        return slots;
    }

    // ------------------------------------------------------------
    // Recipe interface (datapack integration; crafting not used)
    // ------------------------------------------------------------

    @Override
    public boolean matches(Container container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess registryAccess) {
        return createResultStack();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return false;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return createResultStack();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> out = NonNullList.create();
        for (CountedIngredient ci : ingredients) {
            out.add(ci.ingredient);
        }
        return out;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CauldronBrewRecipeSerializer.INSTANCE;
    }

    @Override
    public RecipeType<?> getType() {
        return CauldronBrewRecipeType.INSTANCE;
    }

    /**
     * Mark as "special" so Minecraft's recipe book doesn't attempt to categorize it,
     * which prevents the client warning:
     *   Unknown recipe category: crookedcraft:cauldron_brewing/...
     */
    @Override
    public boolean isSpecial() {
        return true;
    }
}
