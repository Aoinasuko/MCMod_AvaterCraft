package dev.avatarcraft.npc.ai;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;

/** Reads registered recipe ingredients/results; never creates gameplay items. */
final class NpcRecipes {
  static ItemStack output(CraftingRecipe recipe) {
    var p = recipe.placementInfo();
    if (p.isImpossibleToPlace()
        || p.ingredients().isEmpty()
        || p.ingredients().size() > 9
        || recipe.isSpecial()) return ItemStack.EMPTY;
    int width =
        recipe instanceof ShapedRecipe shaped
            ? shaped.getWidth()
            : Math.min(3, p.ingredients().size());
    int height =
        recipe instanceof ShapedRecipe shaped
            ? shaped.getHeight()
            : (p.ingredients().size() + width - 1) / width;
    var grid = new ArrayList<ItemStack>(Collections.nCopies(width * height, ItemStack.EMPTY));
    for (int i = 0; i < p.slotsToIngredientIndex().size(); i++) {
      int index = p.slotsToIngredientIndex().getInt(i);
      if (index >= 0) {
        var item = p.ingredients().get(index).items().findFirst();
        if (item.isEmpty()) return ItemStack.EMPTY;
        grid.set(i, new ItemStack(item.get()));
      }
    }
    try {
      return recipe.assemble(CraftingInput.of(width, height, grid));
    } catch (RuntimeException unsupported) {
      return ItemStack.EMPTY;
    }
  }

  static String id(Item item) {
    return BuiltInRegistries.ITEM.getKey(item).toString();
  }

  static Item item(String id) {
    var key = Identifier.tryParse(id);
    if (key == null || !BuiltInRegistries.ITEM.containsKey(key))
      throw new IllegalArgumentException("Unknown item: " + id);
    return BuiltInRegistries.ITEM.getValue(key);
  }

  static List<RecipeHolder<?>> crafting(ServerLevel level, Item item) {
    return level.getServer().getRecipeManager().getRecipes().stream()
        .filter(h -> h.value() instanceof CraftingRecipe c && output(c).is(item))
        .sorted(
            Comparator.comparingInt(
                h -> h.id().identifier().equals(BuiltInRegistries.ITEM.getKey(item)) ? 0 : 1))
        .limit(12)
        .toList();
  }
}
