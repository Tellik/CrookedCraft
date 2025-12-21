package com.tellik.crookedcraft.brewing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class CauldronBrewRecipeSerializer implements RecipeSerializer<CauldronBrewRecipe> {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final CauldronBrewRecipeSerializer INSTANCE = new CauldronBrewRecipeSerializer();

    private CauldronBrewRecipeSerializer() {}

    @Override
    public CauldronBrewRecipe fromJson(ResourceLocation id, JsonObject json) {
        String liquidStr = GsonHelper.getAsString(json, "liquid", "minecraft:water");
        ResourceLocation liquid = ResourceLocation.tryParse(liquidStr);
        if (liquid == null) {
            LOGGER.warn("[crookedcraft] Invalid liquid '{}' in recipe {}. Defaulting to minecraft:water.", liquidStr, id);
            liquid = ResourceLocation.fromNamespaceAndPath("minecraft", "water");
        }

        List<CauldronBrewRecipe.CountedIngredient> ingredients = new ArrayList<>();
        JsonArray ingArr = GsonHelper.getAsJsonArray(json, "ingredients");
        for (int i = 0; i < ingArr.size(); i++) {
            JsonObject ingObj = GsonHelper.convertToJsonObject(ingArr.get(i), "ingredient");
            int count = GsonHelper.getAsInt(ingObj, "count", 1);

            // vanilla-style Ingredient JSON (item/tag), plus optional "count"
            JsonObject copyForIngredient = ingObj.deepCopy();
            copyForIngredient.remove("count");
            Ingredient ing = Ingredient.fromJson(copyForIngredient);

            ingredients.add(new CauldronBrewRecipe.CountedIngredient(ing, count));
        }

        JsonObject res = GsonHelper.getAsJsonObject(json, "result");

        String itemStr = GsonHelper.getAsString(res, "item", "minecraft:potion");
        ResourceLocation itemId = ResourceLocation.tryParse(itemStr);

        // Guaranteed non-null fallback.
        Item outItem = Items.POTION;

        if (itemId != null) {
            Item resolved = ForgeRegistries.ITEMS.getValue(itemId);
            if (resolved != null) {
                outItem = resolved;
            } else {
                LOGGER.warn("[crookedcraft] Invalid result item '{}' in recipe {}. Defaulting to minecraft:potion.", itemStr, id);
            }
        } else {
            LOGGER.warn("[crookedcraft] Invalid result item '{}' in recipe {}. Defaulting to minecraft:potion.", itemStr, id);
        }

        ResourceLocation potionId = null;
        if (res.has("potion")) {
            String potStr = GsonHelper.getAsString(res, "potion");
            potionId = ResourceLocation.tryParse(potStr);
            if (potionId == null) {
                LOGGER.warn("[crookedcraft] Invalid potion '{}' in recipe {}. Ignoring potion.", potStr, id);
            }
        }

        return new CauldronBrewRecipe(id, liquid, ingredients, outItem, potionId);
    }

    @Override
    public @Nullable CauldronBrewRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        // If a packet is corrupted/malformed, we return null to match the @Nullable contract.
        // This keeps IDE inspections honest and avoids hard crashes on bad input.
        ResourceLocation liquid = buf.readResourceLocation();

        int count = buf.readVarInt();
        if (count < 0 || count > 512) {
            LOGGER.warn("[crookedcraft] fromNetwork: invalid ingredient count {} for recipe {}. Dropping recipe.", count, id);
            return null;
        }

        List<CauldronBrewRecipe.CountedIngredient> ingredients = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Ingredient ing = Ingredient.fromNetwork(buf);
            int c = buf.readVarInt();
            if (c <= 0 || c > 64_000) {
                LOGGER.warn("[crookedcraft] fromNetwork: invalid ingredient stack count {} for recipe {}. Dropping recipe.", c, id);
                return null;
            }
            ingredients.add(new CauldronBrewRecipe.CountedIngredient(ing, c));
        }

        ResourceLocation itemId = buf.readResourceLocation();

        // Guaranteed non-null fallback.
        Item outItem = Items.POTION;
        Item resolved = ForgeRegistries.ITEMS.getValue(itemId);
        if (resolved != null) {
            outItem = resolved;
        }

        boolean hasPotion = buf.readBoolean();
        ResourceLocation potionId = hasPotion ? buf.readResourceLocation() : null;

        return new CauldronBrewRecipe(id, liquid, ingredients, outItem, potionId);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf, CauldronBrewRecipe recipe) {
        buf.writeResourceLocation(recipe.getLiquid());

        buf.writeVarInt(recipe.getCountedIngredients().size());
        for (CauldronBrewRecipe.CountedIngredient ci : recipe.getCountedIngredients()) {
            ci.ingredient.toNetwork(buf);
            buf.writeVarInt(ci.count);
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(recipe.getResultItemItem());
        if (itemId == null) {
            itemId = ForgeRegistries.ITEMS.getKey(Items.POTION);
        }
        if (itemId == null) {
            // Ultra-defensive fallback; should never happen.
            itemId = ResourceLocation.fromNamespaceAndPath("minecraft", "potion");
        }
        buf.writeResourceLocation(itemId);

        ResourceLocation potionId = recipe.getPotionId();
        buf.writeBoolean(potionId != null);
        if (potionId != null) {
            buf.writeResourceLocation(potionId);
        }
    }
}
