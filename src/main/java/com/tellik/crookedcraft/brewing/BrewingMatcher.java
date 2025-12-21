package com.tellik.crookedcraft.brewing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public final class BrewingMatcher {
    private BrewingMatcher() {}

    public static final ResourceLocation WATER_LIQUID = ResourceLocation.fromNamespaceAndPath("minecraft", "water");

    public enum AddOutcome {
        NOT_IN_ANY_RECIPE,   // do not consume
        ACCEPTED,            // consumed, batch still brewing
        READY,               // consumed, batch now ready (exactly one match)
        DOOMED,              // consumed, batch now ruined
        AMBIGUOUS            // do not consume? in v2 we doom without consuming because it's a data error
    }

    public static final class AddResult {
        public final AddOutcome outcome;
        public final int candidatesBefore;
        public final int candidatesAfter;
        public final ResourceLocation matchedRecipeId; // only when READY

        public AddResult(AddOutcome outcome, int before, int after, ResourceLocation matchedRecipeId) {
            this.outcome = outcome;
            this.candidatesBefore = before;
            this.candidatesAfter = after;
            this.matchedRecipeId = matchedRecipeId;
        }
    }

    public static List<CauldronBrewRecipe> recipesForLiquid(ServerLevel level, ResourceLocation liquid) {
        List<CauldronBrewRecipe> all = level.getRecipeManager().getAllRecipesFor(CauldronBrewRecipeType.INSTANCE);
        List<CauldronBrewRecipe> out = new ArrayList<>();
        for (CauldronBrewRecipe r : all) {
            if (r.getLiquid().equals(liquid)) out.add(r);
        }
        return out;
    }

    public static boolean itemAppearsInAnyRecipeForLiquid(ServerLevel level, ResourceLocation liquid, Item item) {
        ItemStack stack = new ItemStack(item);
        for (CauldronBrewRecipe r : recipesForLiquid(level, liquid)) {
            for (Ingredient slot : r.expandToSlots()) {
                if (slot.test(stack)) return true;
            }
        }
        return false;
    }

    public static List<CauldronBrewRecipe> filterCandidates(ServerLevel level,
                                                            ResourceLocation liquid,
                                                            Map<ResourceLocation, Integer> currentIngredients) {
        List<CauldronBrewRecipe> base = recipesForLiquid(level, liquid);
        List<CauldronBrewRecipe> out = new ArrayList<>();

        for (CauldronBrewRecipe r : base) {
            if (canSatisfy(r, currentIngredients)) {
                out.add(r);
            }
        }

        return out;
    }

    public static boolean isExactMatch(CauldronBrewRecipe recipe, Map<ResourceLocation, Integer> currentIngredients) {
        int neededSlots = recipe.expandToSlots().size();
        int currentCount = totalCount(currentIngredients);
        if (currentCount != neededSlots) return false;
        return canSatisfy(recipe, currentIngredients);
    }

    public static AddResult tryAddIngredient(ServerLevel level,
                                             ResourceLocation liquid,
                                             Map<ResourceLocation, Integer> currentIngredients,
                                             Item ingredientItem) {

        List<CauldronBrewRecipe> candidatesBefore = filterCandidates(level, liquid, currentIngredients);
        int before = candidatesBefore.size();

        // If item isn't in any recipe for this liquid at all -> don't consume.
        if (!itemAppearsInAnyRecipeForLiquid(level, liquid, ingredientItem)) {
            return new AddResult(AddOutcome.NOT_IN_ANY_RECIPE, before, before, null);
        }

        // Simulate add
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(ingredientItem);
        if (itemId == null) {
            return new AddResult(AddOutcome.NOT_IN_ANY_RECIPE, before, before, null);
        }

        Map<ResourceLocation, Integer> next = new HashMap<>(currentIngredients);
        next.put(itemId, next.getOrDefault(itemId, 0) + 1);

        List<CauldronBrewRecipe> candidatesAfter = filterCandidates(level, liquid, next);
        int after = candidatesAfter.size();

        if (after == 0) {
            return new AddResult(AddOutcome.DOOMED, before, 0, null);
        }

        // Check exact matches among candidatesAfter
        List<CauldronBrewRecipe> exact = new ArrayList<>();
        for (CauldronBrewRecipe r : candidatesAfter) {
            if (isExactMatch(r, next)) exact.add(r);
        }

        if (exact.size() > 1) {
            // Data issue: two recipes have identical match space.
            // v2: we treat as ambiguous and do NOT consume (so player isn't punished for pack bug).
            return new AddResult(AddOutcome.AMBIGUOUS, before, after, null);
        }

        if (exact.size() == 1 && after == 1) {
            return new AddResult(AddOutcome.READY, before, after, exact.get(0).getId());
        }

        return new AddResult(AddOutcome.ACCEPTED, before, after, null);
    }

    // --------------------------
    // Matching implementation
    // --------------------------

    /**
     * True if the current ingredient multiset can be assigned into the recipe ingredient slots.
     * This supports tags and overlapping ingredients by doing a small backtracking match.
     * Recipe sizes are expected to be small (witchy brewing, not bulk crafting), so this is acceptable.
     */
    private static boolean canSatisfy(CauldronBrewRecipe recipe, Map<ResourceLocation, Integer> currentIngredients) {
        List<Ingredient> slots = recipe.expandToSlots();
        if (slots.isEmpty()) return currentIngredients.isEmpty();

        // Expand current items into a list of ItemStacks (one per unit)
        List<ItemStack> items = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> e : currentIngredients.entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(e.getKey());
            if (item == null) return false;
            for (int i = 0; i < e.getValue(); i++) {
                items.add(new ItemStack(item));
            }
        }

        // Can't satisfy if more items than slots
        if (items.size() > slots.size()) return false;

        // Sort items by "hardness": items that match fewer slots first (better pruning)
        items.sort(Comparator.comparingInt(a -> countMatchingSlots(a, slots)));

        boolean[] usedSlot = new boolean[slots.size()];
        return backtrackMatch(items, 0, slots, usedSlot);
    }

    private static boolean backtrackMatch(List<ItemStack> items, int idx, List<Ingredient> slots, boolean[] usedSlot) {
        if (idx >= items.size()) return true;

        ItemStack stack = items.get(idx);

        // Try to place this stack into any compatible unused slot
        for (int i = 0; i < slots.size(); i++) {
            if (usedSlot[i]) continue;
            if (!slots.get(i).test(stack)) continue;

            usedSlot[i] = true;
            if (backtrackMatch(items, idx + 1, slots, usedSlot)) return true;
            usedSlot[i] = false;
        }

        return false;
    }

    private static int countMatchingSlots(ItemStack stack, List<Ingredient> slots) {
        int c = 0;
        for (Ingredient ing : slots) {
            if (ing.test(stack)) c++;
        }
        return c;
    }

    private static int totalCount(Map<ResourceLocation, Integer> m) {
        int t = 0;
        for (int v : m.values()) t += v;
        return t;
    }
}
